package com.gahan.song.picker.service;

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

  private final RestTemplate restTemplate = new RestTemplate();
  private String accessToken;
  private Instant tokenExpiresAt = Instant.MIN;

  // Cache playlist track IDs by playlist ID — avoids re-fetching paginated Spotify calls
  private final Map<String, List<String>> playlistCache = new HashMap<>();

  private static final String RECCOBEATS_URL = "https://api.reccobeats.com/v1/track/recommendation";

  // ── Public API ──────────────────────────────────────────────────────────────

  public List<Map<String, Object>> findPlaylistRecommendations(ImageAnalysis analysis, String playlistUrl) {
    try {
      ensureValidToken();
      String playlistId = extractPlaylistId(playlistUrl);
      if (playlistId == null) return findRecommendations(analysis);

      List<String> trackIds = getPlaylistTrackIds(playlistId);
      if (trackIds.isEmpty()) return findRecommendations(analysis);

      Collections.shuffle(trackIds);
      List<String> seeds = trackIds.stream().limit(5).collect(Collectors.toList());

      return reccoBeatsRecommendations(seeds, analysis.moodProfile);

    } catch (Exception e) {
      System.out.println("Playlist recommendations failed: " + e.getMessage());
      return findRecommendations(analysis);
    }
  }

  public List<Map<String, Object>> findRecommendations(ImageAnalysis analysis) {
    try {
      ensureValidToken();

      // Search Spotify for 1-3 seed tracks matching the mood, then use them as ReccoBeats seeds
      String query = extractSearchTerms(analysis);
      List<String> seeds = searchTrackIds(query, 3);
      if (seeds.isEmpty()) return getMockSpotifyTracks();

      return reccoBeatsRecommendations(seeds, analysis.moodProfile);

    } catch (Exception e) {
      System.out.println("Recommendations failed: " + e.getMessage());
      return getMockSpotifyTracks();
    }
  }

  // ── ReccoBeats ───────────────────────────────────────────────────────────────

  private List<Map<String, Object>> reccoBeatsRecommendations(List<String> seedIds,
                                                               Map<String, Double> mood) throws Exception {
    double energy       = mood.getOrDefault("energy",       0.5);
    double valence      = mood.getOrDefault("valence",      0.5);
    double danceability = mood.getOrDefault("danceability", 0.5);
    double acousticness = mood.getOrDefault("acousticness", 0.5);

    String seeds = String.join(",", seedIds);
    String url = RECCOBEATS_URL
            + "?seeds=" + seeds
            + "&size=5"
            + "&energy="       + String.format("%.2f", energy)
            + "&valence="      + String.format("%.2f", valence)
            + "&danceability=" + String.format("%.2f", danceability)
            + "&acousticness=" + String.format("%.2f", acousticness);

    ResponseEntity<Map> response = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);

    List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
    System.out.println("ReccoBeats returned " + (content != null ? content.size() : 0) + " tracks");
    return parseReccoBeats(content);
  }

  private List<Map<String, Object>> parseReccoBeats(List<Map<String, Object>> content) {
    List<Map<String, Object>> tracks = new ArrayList<>();
    if (content == null) return tracks;

    for (Map<String, Object> item : content) {
      List<Map<String, Object>> artists = (List<Map<String, Object>>) item.get("artists");
      String artist = artists != null && !artists.isEmpty() ? (String) artists.get(0).get("name") : "Unknown";

      Map<String, Object> track = new HashMap<>();
      track.put("name",        item.get("trackTitle"));
      track.put("artist",      artist);
      track.put("spotify_url", item.get("href"));
      track.put("preview_url", null);
      tracks.add(track);
    }
    return tracks;
  }

  // ── Spotify helpers ───────────────────────────────────────────────────────────

  private List<String> searchTrackIds(String query, int limit) throws Exception {
    String url = "https://api.spotify.com/v1/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&type=track&limit=" + limit;
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    List<Map<String, Object>> items = (List<Map<String, Object>>)
            ((Map<String, Object>) response.getBody().get("tracks")).get("items");

    return items.stream()
            .map(item -> (String) item.get("id"))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
  }

  private List<String> getPlaylistTrackIds(String playlistId) throws Exception {
    if (playlistCache.containsKey(playlistId)) {
      System.out.println("Playlist cache hit for " + playlistId);
      return playlistCache.get(playlistId);
    }

    List<String> ids = new ArrayList<>();
    String url = "https://api.spotify.com/v1/playlists/" + playlistId
            + "/tracks?limit=100&fields=items(track(id)),next";

    while (url != null) {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      Map<String, Object> body = response.getBody();

      for (Map<String, Object> item : (List<Map<String, Object>>) body.get("items")) {
        Map<String, Object> track = (Map<String, Object>) item.get("track");
        if (track != null && track.get("id") != null) ids.add((String) track.get("id"));
      }
      url = (String) body.get("next");
    }

    System.out.println("Loaded " + ids.size() + " track IDs from playlist, caching");
    playlistCache.put(playlistId, ids);
    return ids;
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
