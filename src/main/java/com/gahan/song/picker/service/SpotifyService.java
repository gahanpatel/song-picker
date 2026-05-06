package com.gahan.song.picker.service;

import com.gahan.song.picker.model.ImageAnalysis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

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

  // ── Public API ──────────────────────────────────────────────────────────────

  public List<Map<String, Object>> findPlaylistRecommendations(ImageAnalysis analysis, String playlistUrl) {
    try {
      ensureValidToken();
      String playlistId = extractPlaylistId(playlistUrl);
      if (playlistId == null) return findRecommendations(analysis);

      List<String> trackIds = getPlaylistTrackIds(playlistId);
      if (trackIds.isEmpty()) return findRecommendations(analysis);

      // Pick up to 5 random tracks from the playlist as seeds
      Collections.shuffle(trackIds);
      String seeds = trackIds.stream().limit(5).collect(Collectors.joining(","));

      return getRecommendations("seed_tracks", seeds, analysis.moodProfile);

    } catch (Exception e) {
      System.out.println("Playlist recommendations failed: " + e.getMessage());
      return findRecommendations(analysis);
    }
  }

  public List<Map<String, Object>> findRecommendations(ImageAnalysis analysis) {
    try {
      ensureValidToken();
      String genres = moodToGenres(analysis.moodProfile);
      return getRecommendations("seed_genres", genres, analysis.moodProfile);
    } catch (Exception e) {
      return getMockSpotifyTracks();
    }
  }

  // ── Recommendations ─────────────────────────────────────────────────────────

  private List<Map<String, Object>> getRecommendations(String seedType, String seeds,
                                                        Map<String, Double> mood) throws Exception {
    double energy       = mood.getOrDefault("energy",       0.5);
    double valence      = mood.getOrDefault("valence",      0.5);
    double danceability = mood.getOrDefault("danceability", 0.5);
    double acousticness = mood.getOrDefault("acousticness", 0.5);
    double bpm          = 60 + mood.getOrDefault("tempo",   0.5) * 120; // 0–1 → 60–180 BPM

    String url = "https://api.spotify.com/v1/recommendations"
            + "?" + seedType + "=" + seeds
            + "&limit=5"
            + "&target_energy="       + String.format("%.2f", energy)
            + "&target_valence="      + String.format("%.2f", valence)
            + "&target_danceability=" + String.format("%.2f", danceability)
            + "&target_acousticness=" + String.format("%.2f", acousticness)
            + "&target_tempo="        + String.format("%.0f", bpm);

    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    List<Map<String, Object>> tracks = parseTracks(
            (List<Map<String, Object>>) response.getBody().get("tracks"));
    System.out.println("Recommendations returned " + tracks.size() + " tracks via " + seedType);
    return tracks;
  }

  private String moodToGenres(Map<String, Double> mood) {
    double energy       = mood.getOrDefault("energy",       0.5);
    double valence      = mood.getOrDefault("valence",      0.5);
    double acousticness = mood.getOrDefault("acousticness", 0.5);

    List<String> genres = new ArrayList<>();

    if (acousticness > 0.6) {
      genres.add("acoustic");
      genres.add(energy < 0.4 ? "folk" : "indie");
    } else if (energy > 0.7 && valence > 0.6) {
      genres.add("pop");
      genres.add("dance");
    } else if (energy > 0.7) {
      genres.add("rock");
      genres.add("work-out");
    } else if (energy < 0.3 && valence < 0.4) {
      genres.add("sad");
      genres.add("ambient");
    } else if (energy < 0.3) {
      genres.add("ambient");
      genres.add("sleep");
    } else if (valence > 0.65) {
      genres.add("pop");
      genres.add("happy");
    } else {
      genres.add("indie");
      genres.add("chill");
    }

    return String.join(",", genres);
  }

  // ── Spotify API helpers ──────────────────────────────────────────────────────

  private List<String> getPlaylistTrackIds(String playlistId) throws Exception {
    List<String> ids = new ArrayList<>();
    String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=100&fields=items(track(id)),next";

    while (url != null) {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      Map<String, Object> body = response.getBody();

      List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
      for (Map<String, Object> item : items) {
        Map<String, Object> track = (Map<String, Object>) item.get("track");
        if (track != null && track.get("id") != null) ids.add((String) track.get("id"));
      }
      url = (String) body.get("next");
    }
    System.out.println("Loaded " + ids.size() + " track IDs from playlist");
    return ids;
  }

  private List<Map<String, Object>> parseTracks(List<Map<String, Object>> items) {
    List<Map<String, Object>> tracks = new ArrayList<>();
    if (items == null) return tracks;
    for (Map<String, Object> item : items) {
      Map<String, Object> track = new HashMap<>();
      track.put("name",        item.get("name"));
      track.put("artist",      getArtistName(item));
      track.put("preview_url", item.get("preview_url"));
      track.put("spotify_url", getSpotifyUrl(item));
      tracks.add(track);
    }
    return tracks;
  }

  // ── Auth ─────────────────────────────────────────────────────────────────────

  private void ensureValidToken() throws Exception {
    if (accessToken == null || Instant.now().isAfter(tokenExpiresAt.minusSeconds(60))) {
      getAccessToken();
    }
  }

  private void getAccessToken() throws Exception {
    String auth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.set("Authorization", "Basic " + auth);

    ResponseEntity<Map> response = restTemplate.postForEntity(
            "https://accounts.spotify.com/api/token",
            new HttpEntity<>("grant_type=client_credentials", headers),
            Map.class);

    accessToken = (String) response.getBody().get("access_token");
    Integer expiresIn = (Integer) response.getBody().get("expires_in");
    tokenExpiresAt = Instant.now().plusSeconds(expiresIn != null ? expiresIn : 3600);
  }

  // ── Utilities ────────────────────────────────────────────────────────────────

  private String extractPlaylistId(String url) {
    if (url == null || url.isBlank()) return null;
    url = url.trim();
    if (url.contains("playlist/")) return url.split("playlist/")[1].split("[?]")[0];
    if (url.contains("playlist:")) return url.split("playlist:")[1];
    return null;
  }

  private String getArtistName(Map<String, Object> item) {
    List<Map<String, Object>> artists = (List<Map<String, Object>>) item.get("artists");
    return (String) artists.get(0).get("name");
  }

  private String getSpotifyUrl(Map<String, Object> item) {
    Map<String, Object> urls = (Map<String, Object>) item.get("external_urls");
    return (String) urls.get("spotify");
  }

  private List<Map<String, Object>> getMockSpotifyTracks() {
    return List.of(
            Map.of("name", "Chill Vibes", "artist", "Mock Artist", "preview_url", "", "spotify_url", ""),
            Map.of("name", "Peaceful Mind", "artist", "Relaxation Songs", "preview_url", "", "spotify_url", "")
    );
  }
}
