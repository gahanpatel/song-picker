package com.gahan.song.picker;

import com.gahan.song.picker.model.ImageAnalysis;
import com.gahan.song.picker.service.SpotifyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SpotifyServiceTest {

  @Autowired
  private SpotifyService spotifyService;

  @Test
  void contextLoads() {
    assertNotNull(spotifyService);
  }

  @Test
  void findRecommendations_returnsUpToFiveTracks() throws Exception {
    ImageAnalysis analysis = new ImageAnalysis(
            "A calm peaceful sunset scene with warm golden light.",
            Map.of("energy", 0.2, "valence", 0.7, "danceability", 0.2, "acousticness", 0.8, "tempo", 0.2)
    );

    List<Map<String, Object>> tracks = spotifyService.findRecommendations(analysis);

    assertNotNull(tracks);
    assertFalse(tracks.isEmpty(), "Should return at least one track");
    assertTrue(tracks.size() <= 5, "Should return at most 5 tracks");

    Map<String, Object> first = tracks.get(0);
    assertTrue(first.containsKey("name"),        "Track should have name");
    assertTrue(first.containsKey("artist"),      "Track should have artist");
    assertTrue(first.containsKey("spotify_url"), "Track should have spotify_url");
    System.out.println("Returned " + tracks.size() + " tracks, first: " + first.get("name") + " – " + first.get("artist"));
  }

  @Test
  void findRecommendations_highEnergy_returnsTracks() {
    ImageAnalysis analysis = new ImageAnalysis(
            "An intense concert with flashing lights and a roaring crowd.",
            Map.of("energy", 0.9, "valence", 0.8, "danceability", 0.85, "acousticness", 0.1, "tempo", 0.9)
    );

    List<Map<String, Object>> tracks = spotifyService.findRecommendations(analysis);

    assertNotNull(tracks);
    assertFalse(tracks.isEmpty());
    tracks.forEach(t -> {
      assertNotNull(t.get("name"));
      assertNotNull(t.get("artist"));
    });
  }

  @Test
  void findRecommendations_noIdExposedInResponse() {
    ImageAnalysis analysis = new ImageAnalysis(
            "Rainy window with a cup of coffee.",
            Map.of("energy", 0.3, "valence", 0.4, "danceability", 0.2, "acousticness", 0.7, "tempo", 0.3)
    );

    List<Map<String, Object>> tracks = spotifyService.findRecommendations(analysis);

    tracks.forEach(t -> assertFalse(t.containsKey("id"), "Internal Spotify ID should not be exposed"));
  }

  @Test
  void findPlaylistRecommendations_invalidUrl_fallsBackToSearch() {
    ImageAnalysis analysis = new ImageAnalysis(
            "A bright sunny day at the beach.",
            Map.of("energy", 0.7, "valence", 0.8, "danceability", 0.6, "acousticness", 0.4, "tempo", 0.6)
    );

    List<Map<String, Object>> tracks = spotifyService.findPlaylistRecommendations(analysis, "not-a-valid-url");

    assertNotNull(tracks);
    assertFalse(tracks.isEmpty(), "Should fall back to search results");
  }

  @Test
  void findPlaylistRecommendations_realPlaylist_returnsFiveTracks() {
    ImageAnalysis analysis = new ImageAnalysis(
            "Calm evening with soft lighting.",
            Map.of("energy", 0.3, "valence", 0.6, "danceability", 0.3, "acousticness", 0.7, "tempo", 0.3)
    );

    // Spotify's own public "Chill Hits" playlist
    String playlistUrl = "https://open.spotify.com/playlist/37i9dQZF1DX4WYpdgoIcn6";

    List<Map<String, Object>> tracks = spotifyService.findPlaylistRecommendations(analysis, playlistUrl);

    assertNotNull(tracks);
    assertFalse(tracks.isEmpty());
    assertTrue(tracks.size() <= 5);
    System.out.println("Playlist returned " + tracks.size() + " tracks:");
    tracks.forEach(t -> System.out.println("  " + t.get("name") + " – " + t.get("artist")));
  }
}
