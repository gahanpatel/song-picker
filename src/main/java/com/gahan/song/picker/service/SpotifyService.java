package com.gahan.song.picker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SpotifyService {

  @Value("${spotify.client.id}")
  private String clientId;

  @Value("${spotify.client.secret}")
  private String clientSecret;

  @Autowired
  private AcousticBrainzService acousticBrainzService;

  private final RestTemplate restTemplate = new RestTemplate();
  private String accessToken;

  public List<Map<String, Object>> findPlaylistRecommendations(String aiAnalysis, String playlistUrl) {
    try {
      if (accessToken == null) {
        getAccessToken();
      }

      String playlistId = extractPlaylistId(playlistUrl);
      System.out.println("=== PLAYLIST DEBUG ===");
      System.out.println("Extracted ID: " + playlistId);

      if (playlistId == null) {
        System.out.println("No valid playlist ID, using general search");
        return findRecommendations(aiAnalysis);
      }

      List<Map<String, Object>> playlistTracks = getPlaylistTracks(playlistId);
      System.out.println("Found " + playlistTracks.size() + " tracks in playlist");

      if (playlistTracks.isEmpty()) {
        return findRecommendations(aiAnalysis);
      }

      List<Map<String, Object>> matches = matchWithHybridApproach(playlistTracks, aiAnalysis);
      System.out.println("Hybrid matching complete, returning " + matches.size() + " tracks");

      return matches;

    } catch (Exception e) {
      System.out.println("ERROR in playlist processing: " + e.getMessage());
      e.printStackTrace();
      return findRecommendations(aiAnalysis);
    }
  }

  private List<Map<String, Object>> matchWithHybridApproach(List<Map<String, Object>> tracks, String aiAnalysis) {
    System.out.println("=== HYBRID MATCHING (AcousticBrainz + Keywords) ===");
    List<TrackWithScore> scoredTracks = new ArrayList<>();
    MoodProfile targetMood = analyzeMoodProfile(aiAnalysis);

    int acousticBrainzSuccess = 0;
    int keywordFallback = 0;

    for (Map<String, Object> track : tracks) {
      String trackName = (String) track.get("name");
      String artist = (String) track.get("artist");
      double score = 0.0;

      Map<String, Double> audioFeatures = acousticBrainzService.getAudioFeatures(trackName, artist);

      if (audioFeatures != null && !audioFeatures.isEmpty()) {

        score = calculateFeatureMatchScore(targetMood, audioFeatures);
        acousticBrainzSuccess++;
        System.out.println("✓ AcousticBrainz: " + trackName + " (score: " + String.format("%.2f", score) + ")");
      } else {

        score = calculateImprovedScore(track, aiAnalysis.toLowerCase());
        keywordFallback++;
        System.out.println("○ Keyword fallback: " + trackName + " (score: " + String.format("%.2f", score) + ")");
      }

      scoredTracks.add(new TrackWithScore(track, score));
    }

    System.out.println("Results: " + acousticBrainzSuccess + " with AcousticBrainz, " +
            keywordFallback + " with keywords");

    return scoredTracks.stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(5)
            .map(tws -> tws.track)
            .collect(Collectors.toList());
  }

  private double calculateFeatureMatchScore(MoodProfile target, Map<String, Double> features) {
    double energy = features.getOrDefault("energy", 0.5);
    double valence = features.getOrDefault("valence", 0.5);
    double danceability = features.getOrDefault("danceability", 0.5);

    double energyDiff = Math.abs(target.energy - energy);
    double valenceDiff = Math.abs(target.valence - valence);
    double danceabilityDiff = Math.abs(target.danceability - danceability);

    return 1.0 - ((energyDiff + valenceDiff + danceabilityDiff) / 3.0);
  }

  private MoodProfile analyzeMoodProfile(String aiAnalysis) {
    String analysis = aiAnalysis.toLowerCase();

    double energy = 0.5;
    double valence = 0.5;
    double danceability = 0.5;

    if (analysis.contains("energetic") || analysis.contains("vibrant") || analysis.contains("upbeat")) {
      energy = 0.8;
      valence = 0.7;
      danceability = 0.7;
    } else if (analysis.contains("calm") || analysis.contains("peaceful") || analysis.contains("serene")) {
      energy = 0.2;
      valence = 0.6;
      danceability = 0.3;
    } else if (analysis.contains("dramatic") || analysis.contains("intense") || analysis.contains("powerful")) {
      energy = 0.7;
      valence = 0.3;
      danceability = 0.4;
    }

    return new MoodProfile(energy, valence, danceability);
  }

  private double calculateImprovedScore(Map<String, Object> track, String analysis) {
    String trackName = ((String) track.get("name")).toLowerCase();
    String artist = ((String) track.get("artist")).toLowerCase();
    String combined = trackName + " " + artist;
    double score = Math.random() * 0.5; // Add variety

    if (analysis.contains("energetic") || analysis.contains("vibrant") ||
            analysis.contains("upbeat") || analysis.contains("bright") ||
            analysis.contains("lively") || analysis.contains("joyful")) {

      if (combined.matches(".*(dance|party|beat|pump|energy|power).*")) score += 3.0;
      if (combined.matches(".*(pop|rock|edm|electronic).*") && !combined.contains("slow"))
        score += 2.0;
      if (combined.matches(".*(fast|high|up|jump|move).*")) score += 1.5;
    }

    if (analysis.contains("peaceful") || analysis.contains("calm") ||
            analysis.contains("serene") || analysis.contains("tranquil") ||
            analysis.contains("gentle") || analysis.contains("soft")) {

      if (combined.matches(".*(acoustic|piano|guitar|strings).*")) score += 3.0;
      if (combined.matches(".*(chill|relax|calm|quiet|soft|gentle).*")) score += 2.5;
      if (combined.matches(".*(ambient|meditation|spa|sleep).*")) score += 2.0;
      if (combined.matches(".*(slow|ballad).*")) score += 1.5;
    }

    if (analysis.contains("dramatic") || analysis.contains("intense") ||
            analysis.contains("powerful") || analysis.contains("bold")) {

      if (combined.matches(".*(epic|symphony|orchestra|cinematic).*")) score += 3.0;
      if (combined.matches(".*(dramatic|intense|powerful|heavy).*")) score += 2.5;
      if (combined.matches(".*(dark|metal|rock).*")) score += 2.0;
    }

    if (analysis.contains("romantic") || analysis.contains("warm") ||
            analysis.contains("intimate") || analysis.contains("sunset")) {

      if (combined.matches(".*(love|heart|romance|kiss).*")) score += 3.0;
      if (combined.matches(".*(slow|ballad|tender|sweet).*")) score += 2.0;
      if (combined.matches(".*(jazz|soul|r&b).*")) score += 1.5;
    }

    return score;
  }

  public List<Map<String, Object>> findRecommendations(String aiAnalysis) {
    try {
      if (accessToken == null) {
        getAccessToken();
      }

      String searchQuery = extractSearchTerms(aiAnalysis);
      return searchTracks(searchQuery);

    } catch (Exception e) {
      return getMockSpotifyTracks();
    }
  }


  private String extractPlaylistId(String playlistUrl) {
    if (playlistUrl == null || playlistUrl.isEmpty()) {
      return null;
    }

    playlistUrl = playlistUrl.trim();

    if (playlistUrl.contains("playlist/")) {
      String[] parts = playlistUrl.split("playlist/");
      if (parts.length > 1) {
        return parts[1].split("\\?")[0];
      }
    } else if (playlistUrl.contains("playlist:")) {
      String[] parts = playlistUrl.split("playlist:");
      if (parts.length > 1) {
        return parts[1];
      }
    }

    return null;
  }


  private List<Map<String, Object>> getPlaylistTracks(String playlistId) throws Exception {
    List<Map<String, Object>> allTracks = new ArrayList<>();
    String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=100";

    while (url != null) {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      HttpEntity<String> entity = new HttpEntity<>(headers);

      ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

      allTracks.addAll(parsePlaylistResponse(response.getBody()));

      url = (String) response.getBody().get("next");
    }

    System.out.println("Retrieved " + allTracks.size() + " total tracks from playlist");
    return allTracks;
  }

  private List<Map<String, Object>> parsePlaylistResponse(Map<String, Object> response) {
    List<Map<String, Object>> tracks = new ArrayList<>();

    List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");

    for (Map<String, Object> item : items) {
      Map<String, Object> track = (Map<String, Object>) item.get("track");
      if (track != null) {
        Map<String, Object> trackData = new HashMap<>();
        trackData.put("name", track.get("name"));
        trackData.put("artist", getArtistName(track));
        trackData.put("preview_url", track.get("preview_url"));
        trackData.put("spotify_url", getSpotifyUrl(track));
        tracks.add(trackData);
      }
    }

    return tracks;
  }

  private void getAccessToken() throws Exception {
    String url = "https://accounts.spotify.com/api/token";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    String auth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    headers.set("Authorization", "Basic " + auth);

    String body = "grant_type=client_credentials";
    HttpEntity<String> entity = new HttpEntity<>(body, headers);

    ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
    accessToken = (String) response.getBody().get("access_token");
  }

  private List<Map<String, Object>> searchTracks(String query) throws Exception {
    String url = "https://api.spotify.com/v1/search?q=" + query + "&type=track&limit=5";

    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    HttpEntity<String> entity = new HttpEntity<>(headers);

    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

    return parseSpotifyResponse(response.getBody());
  }

  private List<Map<String, Object>> parseSpotifyResponse(Map<String, Object> response) {
    List<Map<String, Object>> tracks = new ArrayList<>();

    Map<String, Object> tracksData = (Map<String, Object>) response.get("tracks");
    List<Map<String, Object>> items = (List<Map<String, Object>>) tracksData.get("items");

    for (Map<String, Object> item : items) {
      Map<String, Object> track = new HashMap<>();
      track.put("name", item.get("name"));
      track.put("artist", getArtistName(item));
      track.put("preview_url", item.get("preview_url"));
      track.put("spotify_url", getSpotifyUrl(item));
      tracks.add(track);
    }

    return tracks;
  }

  private String extractSearchTerms(String aiAnalysis) {
    if (aiAnalysis.contains("pop")) return "pop music";
    if (aiAnalysis.contains("rock")) return "rock music";
    if (aiAnalysis.contains("jazz")) return "jazz";
    if (aiAnalysis.contains("electronic")) return "electronic music";
    if (aiAnalysis.contains("acoustic")) return "acoustic";
    if (aiAnalysis.contains("indie")) return "indie music";
    return "chill music";
  }

  private String getArtistName(Map<String, Object> item) {
    List<Map<String, Object>> artists = (List<Map<String, Object>>) item.get("artists");
    return (String) artists.get(0).get("name");
  }

  private String getSpotifyUrl(Map<String, Object> item) {
    Map<String, Object> external_urls = (Map<String, Object>) item.get("external_urls");
    return (String) external_urls.get("spotify");
  }


  private List<Map<String, Object>> getMockSpotifyTracks() {
    return List.of(
            Map.of("name", "Chill Vibes", "artist", "Mock Artist", "preview_url", "", "spotify_url", ""),
            Map.of("name", "Peaceful Mind", "artist", "Relaxation Songs", "preview_url", "", "spotify_url", "")
    );
  }


  private static class MoodProfile {
    double energy, valence, danceability;

    MoodProfile(double energy, double valence, double danceability) {
      this.energy = energy;
      this.valence = valence;
      this.danceability = danceability;
    }
  }

  private static class TrackWithScore {
    Map<String, Object> track;
    double score;

    TrackWithScore(Map<String, Object> track, double score) {
      this.track = track;
      this.score = score;
    }
  }
}