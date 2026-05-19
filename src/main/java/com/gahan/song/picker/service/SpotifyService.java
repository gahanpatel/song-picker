package com.gahan.song.picker.service;

import com.gahan.song.picker.model.ImageAnalysis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class SpotifyService {

  @Value("${spotify.client.id}")
  private String clientId;

  @Value("${spotify.client.secret}")
  private String clientSecret;

  @Value("${lastfm.api.key:}")
  private String lastFmApiKey;

  private final RestTemplate restTemplate = new RestTemplate();
  private String accessToken;
  private Instant tokenExpiresAt = Instant.MIN;

  private final Map<String, List<Map<String, Object>>> playlistCache = new HashMap<>();
  private final Map<String, List<String>> lastFmTagCache = new ConcurrentHashMap<>();
  private final ExecutorService tagFetcher = Executors.newFixedThreadPool(15);

  private static final String LASTFM_URL = "http://ws.audioscrobbler.com/2.0/";

  // ── Public API ──────────────────────────────────────────────────────────────

  public List<Map<String, Object>> findPlaylistRecommendations(ImageAnalysis analysis, String playlistUrl) {
    try {
      ensureValidToken();
      String playlistId = extractPlaylistId(playlistUrl);
      if (playlistId == null) return findRecommendations(analysis);

      List<Map<String, Object>> tracks = getPlaylistTracks(playlistId);
      if (tracks.isEmpty()) return findRecommendations(analysis);

      if (lastFmApiKey != null && !lastFmApiKey.isBlank()) {
        return scoreByLastFmTags(tracks, analysis, 5);
      }

      // No Last.fm key: return 5 random tracks from the playlist
      List<Map<String, Object>> shuffled = new ArrayList<>(tracks);
      Collections.shuffle(shuffled);
      return shuffled.stream().limit(5)
              .map(t -> { Map<String, Object> r = new HashMap<>(t); r.remove("id"); return r; })
              .collect(Collectors.toList());

    } catch (Exception e) {
      System.out.println("Playlist recommendations failed: " + e.getMessage());
      return findRecommendations(analysis);
    }
  }

  public List<Map<String, Object>> findRecommendations(ImageAnalysis analysis) {
    try {
      ensureValidToken();
      String primaryQuery   = extractSearchTerms(analysis);
      String secondaryQuery = extractSecondaryTerms(analysis);

      // Two searches give a broader, more diverse candidate pool
      List<Map<String, Object>> results = new ArrayList<>(searchTracks(primaryQuery, 8));
      if (!secondaryQuery.equals(primaryQuery)) {
        for (Map<String, Object> t : searchTracks(secondaryQuery, 8)) {
          if (results.stream().noneMatch(r -> r.get("id").equals(t.get("id")))) results.add(t);
        }
      }
      if (results.isEmpty()) return getMockSpotifyTracks();

      if (lastFmApiKey != null && !lastFmApiKey.isBlank()) {
        return scoreByLastFmTags(results, analysis, 5);
      }

      return results.stream().limit(5).collect(Collectors.toList());
    } catch (Exception e) {
      System.out.println("Recommendations failed: " + e.getMessage());
      return getMockSpotifyTracks();
    }
  }

  // ── Last.fm scoring ──────────────────────────────────────────────────────────

  private List<Map<String, Object>> scoreByLastFmTags(List<Map<String, Object>> tracks,
                                                       ImageAnalysis analysis, int limit) {
    List<String> targets = buildTargetTags(analysis.moodProfile, analysis.text);
    System.out.println("Last.fm target tags: " + targets);
    System.out.println("Scoring " + tracks.size() + " tracks...");

    // Fetch all tags in parallel — cached after first run so repeat requests are instant
    List<CompletableFuture<Void>> futures = tracks.stream()
            .map(t -> CompletableFuture.runAsync(
                    () -> getLastFmTags((String) t.get("artist"), (String) t.get("name")), tagFetcher))
            .collect(Collectors.toList());
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    List<Map.Entry<Map<String, Object>, Integer>> scored = new ArrayList<>();
    for (Map<String, Object> track : tracks) {
      List<String> tags = getLastFmTags((String) track.get("artist"), (String) track.get("name"));
      int score = countTagOverlap(tags, targets);
      scored.add(Map.entry(track, score));
    }

    scored.sort((a, b) -> {
      int cmp = b.getValue() - a.getValue();
      if (cmp != 0) return cmp;
      // Break ties alphabetically so results are stable across requests
      return ((String) a.getKey().getOrDefault("name", ""))
              .compareTo((String) b.getKey().getOrDefault("name", ""));
    });
    if (!scored.isEmpty()) {
      Map<String, Object> top = scored.get(0).getKey();
      List<String> topTags = getLastFmTags((String) top.get("artist"), (String) top.get("name"));
      List<String> matched = topTags.stream().filter(tag ->
              targets.stream().anyMatch(t -> tag.contains(t) || t.contains(tag))
      ).collect(Collectors.toList());
      System.out.println("Top match: " + top.get("name") + " – " + top.get("artist")
              + " (score=" + scored.get(0).getValue() + ", matched tags=" + matched + ")");
    }

    return scored.stream()
            .limit(limit)
            .map(e -> {
              Map<String, Object> t = new HashMap<>(e.getKey());
              t.remove("id");
              return t;
            })
            .collect(Collectors.toList());
  }

  private List<String> getLastFmTags(String artist, String trackName) {
    String cacheKey = artist + "::" + trackName;
    if (lastFmTagCache.containsKey(cacheKey)) return lastFmTagCache.get(cacheKey);

    List<String> tags = fetchTags("track.getTopTags", artist, trackName);
    // Artist tags are genre-level, not song-level — only use 2 to give a weak signal,
    // not enough to beat a track that has real mood tags
    if (tags.isEmpty()) tags = fetchArtistTags(artist).stream().limit(2).collect(Collectors.toList());

    lastFmTagCache.put(cacheKey, tags);
    return tags;
  }

  private List<String> fetchTags(String method, String artist, String track) {
    try {
      String url = LASTFM_URL + "?method=" + method + "&format=json"
              + "&api_key=" + lastFmApiKey
              + "&artist=" + URLEncoder.encode(artist != null ? artist : "", StandardCharsets.UTF_8)
              + "&track="  + URLEncoder.encode(track  != null ? track  : "", StandardCharsets.UTF_8);

      ResponseEntity<Map> resp = restTemplate.exchange(
              url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
      return extractTagNames(resp.getBody(), "toptags");
    } catch (Exception e) {
      return List.of();
    }
  }

  private List<String> fetchArtistTags(String artist) {
    try {
      String url = LASTFM_URL + "?method=artist.getTopTags&format=json"
              + "&api_key=" + lastFmApiKey
              + "&artist=" + URLEncoder.encode(artist != null ? artist : "", StandardCharsets.UTF_8);

      ResponseEntity<Map> resp = restTemplate.exchange(
              url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
      return extractTagNames(resp.getBody(), "toptags");
    } catch (Exception e) {
      return List.of();
    }
  }

  private static final int MIN_TAG_COUNT = 10;

  private List<String> extractTagNames(Map<String, Object> body, String key) {
    if (body == null || !body.containsKey(key)) return List.of();
    Map<String, Object> toptags = (Map<String, Object>) body.get(key);
    if (toptags == null) return List.of();
    Object tagObj = toptags.get("tag");
    if (!(tagObj instanceof List)) return List.of();
    return ((List<Map<String, Object>>) tagObj).stream()
            .filter(t -> {
              Object count = t.get("count");
              int c = count instanceof Number ? ((Number) count).intValue() : 0;
              return c >= MIN_TAG_COUNT;
            })
            .map(t -> ((String) t.get("name")).toLowerCase().trim())
            .filter(s -> !s.isBlank())
            .collect(Collectors.toList());
  }

  private static final Set<String> STOP_WORDS = Set.of(
          "the","and","with","that","this","from","have","which","there","their",
          "been","were","they","when","what","will","your","about","also","very",
          "more","some","into","than","then","just","like","over","such","both",
          "each","most","other","these","those","only","even","much","well","here",
          "scene","image","photo","picture","overall","feel","sense","tone","mood"
  );

  private List<String> buildTargetTags(Map<String, Double> mood, String analysisText) {
    List<String> tags = new ArrayList<>();
    double energy       = mood.getOrDefault("energy",       0.5);
    double valence      = mood.getOrDefault("valence",      0.5);
    double danceability = mood.getOrDefault("danceability", 0.5);
    double acousticness = mood.getOrDefault("acousticness", 0.5);

    if (energy > 0.7)
      tags.addAll(List.of("energetic", "upbeat", "intense", "powerful", "driving", "aggressive", "loud"));
    else if (energy < 0.35)
      tags.addAll(List.of("calm", "chill", "peaceful", "relaxing", "mellow", "ambient", "soft", "lo-fi", "quiet"));

    if (valence > 0.7)
      tags.addAll(List.of("happy", "cheerful", "feel-good", "positive", "joyful", "uplifting", "bright", "euphoric"));
    else if (valence < 0.35)
      tags.addAll(List.of("sad", "melancholy", "emotional", "dark", "melancholic", "heartbreak", "lonely", "depressing", "somber"));

    if (danceability > 0.7)
      tags.addAll(List.of("dance", "danceable", "groovy", "energetic", "rhythmic", "bouncy"));

    if (acousticness > 0.7)
      tags.addAll(List.of("acoustic", "unplugged", "organic", "intimate", "warm"));
    else if (acousticness < 0.3)
      tags.addAll(List.of("electronic", "synthetic", "cold", "digital"));

    // Pull descriptive words from GPT's analysis — "cinematic", "ethereal", etc. often match Last.fm tags
    if (analysisText != null) {
      Arrays.stream(analysisText.toLowerCase().replaceAll("[^a-z\\s]", " ").split("\\s+"))
              .filter(w -> w.length() > 4 && !STOP_WORDS.contains(w))
              .forEach(tags::add);
    }

    return tags;
  }

  private int countTagOverlap(List<String> trackTags, List<String> targetTags) {
    int count = 0;
    for (String tag : trackTags) {
      for (String target : targetTags) {
        if (tag.contains(target) || target.contains(tag)) { count++; break; }
      }
    }
    return count;
  }

  // ── Spotify helpers ───────────────────────────────────────────────────────────

  private List<Map<String, Object>> getPlaylistTracks(String playlistId) throws Exception {
    if (playlistCache.containsKey(playlistId)) {
      System.out.println("Playlist cache hit for " + playlistId);
      return playlistCache.get(playlistId);
    }

    List<Map<String, Object>> tracks = new ArrayList<>();
    String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=100";

    while (url != null) {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      Map<String, Object> body = response.getBody();

      for (Map<String, Object> item : (List<Map<String, Object>>) body.get("items")) {
        Map<String, Object> trackData = (Map<String, Object>) item.get("track");
        if (trackData == null || trackData.get("id") == null) continue;

        List<Map<String, Object>> artistList = (List<Map<String, Object>>) trackData.get("artists");
        String artist = (artistList != null && !artistList.isEmpty())
                ? (String) artistList.get(0).get("name") : "Unknown";
        Map<String, Object> extUrls = (Map<String, Object>) trackData.get("external_urls");
        String spotifyUrl = extUrls != null ? (String) extUrls.get("spotify") : "";

        Map<String, Object> track = new HashMap<>();
        track.put("id",          trackData.get("id"));
        track.put("name",        trackData.get("name"));
        track.put("artist",      artist);
        track.put("spotify_url", spotifyUrl);
        track.put("preview_url", null);
        tracks.add(track);
      }
      url = (String) body.get("next");
    }

    System.out.println("Loaded " + tracks.size() + " tracks from playlist, caching");
    playlistCache.put(playlistId, tracks);
    return tracks;
  }

  private List<Map<String, Object>> searchTracks(String query, int limit) throws Exception {
    String url = "https://api.spotify.com/v1/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&type=track&limit=" + limit;
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    List<Map<String, Object>> items = (List<Map<String, Object>>)
            ((Map<String, Object>) response.getBody().get("tracks")).get("items");

    List<Map<String, Object>> tracks = new ArrayList<>();
    for (Map<String, Object> item : items) {
      List<Map<String, Object>> artistList = (List<Map<String, Object>>) item.get("artists");
      String artist = (artistList != null && !artistList.isEmpty())
              ? (String) artistList.get(0).get("name") : "Unknown";
      Map<String, Object> extUrls = (Map<String, Object>) item.get("external_urls");
      String spotifyUrl = extUrls != null ? (String) extUrls.get("spotify") : "";

      Map<String, Object> track = new HashMap<>();
      track.put("id",          item.get("id"));
      track.put("name",        item.get("name"));
      track.put("artist",      artist);
      track.put("spotify_url", spotifyUrl);
      track.put("preview_url", item.get("preview_url"));
      tracks.add(track);
    }
    return tracks;
  }

  private String extractSecondaryTerms(ImageAnalysis analysis) {
    Map<String, Double> m = analysis.moodProfile;
    double energy       = m.getOrDefault("energy",       0.5);
    double valence      = m.getOrDefault("valence",      0.5);
    double acousticness = m.getOrDefault("acousticness", 0.5);

    // Complement the primary query with a different angle
    if (energy > 0.7 && valence > 0.6)  return "feel good anthems";
    if (energy > 0.7)                   return "powerful workout music";
    if (valence < 0.35 && acousticness > 0.5) return "sad acoustic songs";
    if (valence < 0.35)                 return "melancholic indie";
    if (acousticness > 0.7)             return "acoustic singer songwriter";
    if (energy < 0.35)                  return "ambient instrumental relaxing";
    if (valence > 0.65)                 return "feel good pop";
    return "indie atmospheric";
  }

  private String extractSearchTerms(ImageAnalysis analysis) {
    String text = analysis.text.toLowerCase();
    Map<String, Double> m = analysis.moodProfile;

    double energy       = m.getOrDefault("energy",       0.5);
    double valence      = m.getOrDefault("valence",      0.5);
    double acousticness = m.getOrDefault("acousticness", 0.5);

    if (text.contains("jazz"))                                return energy > 0.5 ? "upbeat jazz" : "smooth jazz";
    if (text.contains("classical") || text.contains("orchestra")) return energy > 0.6 ? "epic orchestral" : "classical peaceful";
    if (text.contains("electronic") || text.contains("edm")) return energy < 0.4 ? "ambient electronic" : "electronic dance";
    if (text.contains("hip hop") || text.contains("hip-hop")) return "hip hop";
    if (text.contains("rock"))                                return energy > 0.6 ? "energetic rock" : "alternative rock";
    if (text.contains("acoustic") || text.contains("folk"))  return acousticness > 0.6 ? "acoustic folk" : "indie folk";
    if (text.contains("indie"))                               return valence < 0.4 ? "indie folk" : "indie pop";
    if (text.contains("pop"))                                 return energy > 0.6 ? "upbeat pop" : "pop";

    if (energy > 0.7 && valence > 0.6)  return "upbeat energetic happy";
    if (energy > 0.7)                   return "intense powerful dramatic";
    if (valence < 0.35)                 return "sad melancholy emotional";
    if (acousticness > 0.7)             return "acoustic folk ambient";
    if (energy < 0.35 && valence > 0.5) return "calm peaceful ambient";
    if (valence > 0.65)                 return "happy cheerful bright";
    return "atmospheric mood";
  }

  // ── Auth ──────────────────────────────────────────────────────────────────────

  private void ensureValidToken() throws Exception {
    if (accessToken == null || Instant.now().isAfter(tokenExpiresAt.minusSeconds(60))) getAccessToken();
  }

  private void getAccessToken() throws Exception {
    String auth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.set("Authorization", "Basic " + auth);
    ResponseEntity<Map> response = restTemplate.postForEntity(
            "https://accounts.spotify.com/api/token",
            new HttpEntity<>("grant_type=client_credentials", headers), Map.class);
    accessToken = (String) response.getBody().get("access_token");
    Integer expiresIn = (Integer) response.getBody().get("expires_in");
    tokenExpiresAt = Instant.now().plusSeconds(expiresIn != null ? expiresIn : 3600);
  }

  // ── Utilities ─────────────────────────────────────────────────────────────────

  private String extractPlaylistId(String url) {
    if (url == null || url.isBlank()) return null;
    url = url.trim();
    if (url.contains("playlist/")) return url.split("playlist/")[1].split("[?]")[0];
    if (url.contains("playlist:")) return url.split("playlist:")[1];
    return null;
  }

  private List<Map<String, Object>> getMockSpotifyTracks() {
    return List.of(
            Map.of("name", "Chill Vibes", "artist", "Mock Artist", "preview_url", "", "spotify_url", ""),
            Map.of("name", "Peaceful Mind", "artist", "Relaxation Songs", "preview_url", "", "spotify_url", "")
    );
  }
}
