package com.gahan.song.picker.controller;

import com.gahan.song.picker.service.OpenAIService;
import com.gahan.song.picker.service.SpotifyService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://127.0.0.1:3000")
@RestController
@RequestMapping("/api/image")
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

    System.out.println("=== FILE DEBUG INFO ===");
    System.out.println("Original filename: " + file.getOriginalFilename());
    System.out.println("Content type: " + file.getContentType());
    System.out.println("File size: " + file.getSize());
    System.out.println("Is empty: " + file.isEmpty());

    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body("Please select a file");
    }

    // Remove strict content type checking temporarily to test
    /*
    String contentType = file.getContentType();
    if (contentType == null || !contentType.startsWith("image/")) {
        return ResponseEntity.badRequest().body("Please upload an image file");
    }
    */

    try {
      String analysis = openAIService.analyzeImage(file);

      List<Map<String, Object>> spotifyTracks;
      if (playlistUrl != null && !playlistUrl.isEmpty()) {
        System.out.println("Using playlist-specific search");
        spotifyTracks = spotifyService.findPlaylistRecommendations(analysis, playlistUrl);
      } else {
        System.out.println("Using general Spotify search");
        spotifyTracks = spotifyService.findRecommendations(analysis);
      }

      return ResponseEntity.ok(Map.of(
              "analysis", analysis,
              "spotify_tracks", spotifyTracks
      ));
    } catch (Exception e) {
      System.out.println("Error processing file: " + e.getMessage());
      e.printStackTrace();
      return ResponseEntity.status(500).body("Error: " + e.getMessage());
    }
  }
}