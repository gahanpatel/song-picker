package com.gahan.song.picker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahan.song.picker.model.ImageAnalysis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SpotifyService {

  @Value("${spotify.client.id}")
  private String clientId;

  @Value("${spotify.client.secret}")
  private String clientSecret;

  @Value("${openai.api.key}")
  private String openAiApiKey;

  private final RestTemplate restTemplate = new RestTemplate();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private String accessToken;
  private Instant tokenExpiresAt = Instant.MIN;

  private final Map<String, List<Map<String, Object>>> playlistCache = new HashMap<>();

  private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

  // ── Public API ──────────────────────────────────────────────────────────────

  public List<Map<String, Object>> findRecommendations(ImageAnalysis analysis) {
    try {
      ensureValidToken();

      // GPT suggested specific songs — look each one up directly on Spotify
      if (!analysis.suggestedSongs.isEmpty()) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, String> song : analysis.suggestedSongs) {
          String title = song.get("title");
          String artist = song.get("artist");
          if (title == null || artist == null) continue;
          String query = title + " " + artist;
          List<Map<String, Object>> found = searchTracks(query, 1);
          if (!found.isEmpty()) {
            Map<String, Object> track = new HashMap<>(found.get(0));
            track.remove("id");
            results.add(track);
          }
        }
        if (!results.isEmpty()) {
          System.out.println("Returning " + results.size() + " GPT-suggested tracks");
          return results;
        }
      }

      // Fallback: search by mood terms
      System.out.println("No GPT suggestions, falling back to search");
      List<Map<String, Object>> results = searchTracks(extractSearchTerms(analysis), 5);
      return results.stream().map(t -> { Map<String, Object> r = new HashMap<>(t); r.remove("id"); return r; })
              .collect(Collectors.toList());

    } catch (Exception e) {
      System.out.println("Recommendations failed: " + e.getMessage());
      return getMockSpotifyTracks();
    }
  }

  public List<Map<String, Object>> findPlaylistRecommendations(ImageAnalysis analysis, String playlistUrl) {
    try {
      ensureValidToken();
      String playlistId = extractPlaylistId(playlistUrl);
      if (playlistId == null) return findRecommendations(analysis);

      List<Map<String, Object>> tracks = getPlaylistTracks(playlistId);
      if (tracks.isEmpty()) return findRecommendations(analysis);

      // Ask GPT to pick 5 tracks from the playlist that match the mood
      List<Map<String, Object>> picked = selectFromPlaylistWithGPT(analysis, tracks);
      if (!picked.isEmpty()) return picked;

      // Fallback: return first 5
      return tracks.stream().limit(5)
              .map(t -> { Map<String, Object> r = new HashMap<>(t); r.remove("id"); return r; })
              .collect(Collectors.toList());

    } catch (Exception e) {
      System.out.println("Playlist recommendations failed: " + e.getMessage());
      return findRecommendations(analysis);
    }
  }

  // ── GPT playlist selection ────────────────────────────────────────────────────

  private List<Map<String, Object>> selectFromPlaylistWithGPT(ImageAnalysis analysis,
                                                               List<Map<String, Object>> tracks) {
    try {
      StringBuilder trackList = new StringBuilder();
      for (int i = 0; i < tracks.size(); i++) {
        trackList.append(i).append(". ")
                .append(tracks.get(i).get("name")).append(" — ")
                .append(tracks.get(i).get("artist")).append("\n");
      }

      String prompt = "Image mood: " + analysis.text + "\n\n"
              + formatMoodProfile(analysis.moodProfile)
              + "From these " + tracks.size() + " songs, pick the 5 that best match that mood "
              + "(use both the description and the numeric profile above) and return their indices "
              + "as a JSON array of integers ordered from best to worst match "
              + "(e.g. [42, 3, 88, 17, 65]). Return ONLY the JSON array, nothing else.\n\n"
              + trackList;

      System.out.println("Sending all " + tracks.size() + " tracks to GPT for ranking...");

      Map<String, Object> requestBody = Map.of(
              "model", "gpt-4o-mini",
              "messages", List.of(Map.of("role", "user", "content", prompt)),
              "max_tokens", 30
      );

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(openAiApiKey);

      ResponseEntity<Map> response = restTemplate.postForEntity(
              OPENAI_API_URL, new HttpEntity<>(requestBody, headers), Map.class);

      List<?> choices = (List<?>) response.getBody().get("choices");
      String content = (String) ((Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message")).get("content");
      content = content.trim().replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();

      List<Integer> indices = objectMapper.readValue(content, List.class);
      System.out.println("GPT picked indices: " + indices);

      List<Map<String, Object>> result = new ArrayList<>();
      for (Object idxObj : indices) {
        int idx = ((Number) idxObj).intValue();
        if (idx >= 0 && idx < tracks.size()) {
          Map<String, Object> t = new HashMap<>(tracks.get(idx));
          t.remove("id");
          result.add(t);
        }
      }
      return result;

    } catch (Exception e) {
      System.out.println("GPT playlist selection failed: " + e.getMessage());
      return List.of();
    }
  }

  // Renders the numeric mood profile as labelled 0.0–1.0 targets so GPT has
  // structured criteria to rank against, not just the free-text description.
  private String formatMoodProfile(Map<String, Double> mood) {
    if (mood == null || mood.isEmpty()) return "";
    String[][] specs = {
            {"energy",       "calm/still ↔ intense/energetic"},
            {"valence",      "dark/sad ↔ bright/happy"},
            {"danceability", "still/contemplative ↔ rhythmic/danceable"},
            {"acousticness", "electronic/synthetic ↔ natural/acoustic"},
            {"tempo",        "very slow ↔ very fast-paced"},
    };
    StringBuilder sb = new StringBuilder("Numeric mood profile (0.0–1.0 scale):\n");
    for (String[] spec : specs) {
      Double v = mood.get(spec[0]);
      if (v != null) sb.append(String.format("- %s: %.2f (%s)%n", spec[0], v, spec[1]));
    }
    return sb.append("\n").toString();
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

  private String extractSearchTerms(ImageAnalysis analysis) {
    Map<String, Double> m = analysis.moodProfile;
    double energy       = m.getOrDefault("energy",       0.5);
    double valence      = m.getOrDefault("valence",      0.5);
    double acousticness = m.getOrDefault("acousticness", 0.5);

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
