package com.gahan.song.picker.controller;

import com.gahan.song.picker.service.OpenAIService;
import com.gahan.song.picker.service.SpotifyService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/image")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
public class ImageController {

  @Autowired
  private OpenAIService openAIService;

  @Autowired
  private SpotifyService spotifyService;

  @GetMapping("/test")
  public String test() {
    return "Image controller is working!";
  }

  @PostMapping("/analyze")
  public ResponseEntity<?> analyzeImage(
          @RequestParam("image") MultipartFile file,
          @RequestParam(value = "playlistUrl", required = false) String playlistUrl) {

    System.out.println("=== CONTROLLER RECEIVED REQUEST ===");
    System.out.println("File: " + file.getOriginalFilename());

    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body("Please select a file");
    }

    try {
      String analysis = openAIService.analyzeImage(file);

      List<Map<String, Object>> spotifyTracks;
      if (playlistUrl != null && !playlistUrl.isEmpty()) {
        spotifyTracks = spotifyService.findPlaylistRecommendations(analysis, playlistUrl);
      } else {
        spotifyTracks = spotifyService.findRecommendations(analysis);
      }

      return ResponseEntity.ok(Map.of(
              "analysis", analysis,
              "spotify_tracks", spotifyTracks
      ));
    } catch (Exception e) {
      System.out.println("Error: " + e.getMessage());
      e.printStackTrace();
      return ResponseEntity.status(500).body("Error: " + e.getMessage());
    }
  }
}