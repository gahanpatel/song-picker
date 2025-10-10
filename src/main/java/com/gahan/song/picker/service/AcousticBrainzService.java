package com.gahan.song.picker.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class AcousticBrainzService {

  private final RestTemplate restTemplate = new RestTemplate();

  // Try to get audio features from AcousticBrainz
  public Map<String, Double> getAudioFeatures(String trackName, String artist) {
    try {
      // First, get MusicBrainz ID
      String mbid = getMusicBrainzId(trackName, artist);
      if (mbid == null) {
        return null;
      }

      // Get low-level audio features from AcousticBrainz
      String url = "https://acousticbrainz.org/api/v1/" + mbid + "/low-level";

      ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

      return parseAudioFeatures(response.getBody());

    } catch (Exception e) {
      System.out.println("AcousticBrainz lookup failed for " + trackName + ": " + e.getMessage());
      return null;
    }
  }

  // Get MusicBrainz ID for a track
  private String getMusicBrainzId(String trackName, String artist) {
    try {
      String encodedTrack = URLEncoder.encode(trackName, StandardCharsets.UTF_8);
      String encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8);
      String url = "https://musicbrainz.org/ws/2/recording/?query=recording:" +
              encodedTrack + "%20AND%20artist:" + encodedArtist +
              "&fmt=json&limit=1";

      HttpHeaders headers = new HttpHeaders();
      headers.set("User-Agent", "SongPickerApp/1.0");
      HttpEntity<String> entity = new HttpEntity<>(headers);

      ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

      List<Map<String, Object>> recordings = (List<Map<String, Object>>) response.getBody().get("recordings");
      if (recordings != null && !recordings.isEmpty()) {
        return (String) recordings.get(0).get("id");
      }

    } catch (Exception e) {
      System.out.println("MusicBrainz lookup failed: " + e.getMessage());
    }
    return null;
  }

  // Parse AcousticBrainz response into usable features
  private Map<String, Double> parseAudioFeatures(Map<String, Object> data) {
    Map<String, Double> features = new HashMap<>();

    try {
      Map<String, Object> rhythm = (Map<String, Object>) data.get("rhythm");
      Map<String, Object> tonal = (Map<String, Object>) data.get("tonal");

      // Extract tempo/energy
      if (rhythm != null) {
        Double bpm = getDouble(rhythm, "bpm");
        if (bpm != null) {
          // Normalize BPM to energy scale (0-1)
          // Typical range: 60-180 BPM
          features.put("energy", Math.min(Math.max((bpm - 60) / 120.0, 0.0), 1.0));
        }

        // Danceability from BPM
        if (bpm != null && bpm >= 90 && bpm <= 130) {
          features.put("danceability", 0.8); // Sweet spot for dancing
        } else {
          features.put("danceability", 0.4);
        }
      }

      // Extract valence from key (major = happy, minor = sad)
      if (tonal != null) {
        String key = getString(tonal, "key_key");
        if (key != null) {
          if (key.toLowerCase().contains("major")) {
            features.put("valence", 0.7);
          } else {
            features.put("valence", 0.3);
          }
        }
      }

    } catch (Exception e) {
      System.out.println("Error parsing AcousticBrainz data: " + e.getMessage());
    }

    return features.isEmpty() ? null : features;
  }

  // Helper methods
  private Double getDouble(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return null;
  }

  private String getString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value != null ? value.toString() : null;
  }
}