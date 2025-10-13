package com.gahan.song.picker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;
import java.util.List;

@Service
public class OpenAIService {

  @Value("${openai.api.key}")
  private String apiKey;

  private final RestTemplate restTemplate = new RestTemplate();
  private final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

  public String analyzeImage(MultipartFile file) throws Exception {
    System.out.println("=== OPENAI DEBUG START ===");
    System.out.println("Filename: " + file.getOriginalFilename());
    System.out.println("File size: " + file.getSize() + " bytes");
    System.out.println("Content type: " + file.getContentType());
    System.out.println("API Key exists: " + (apiKey != null));
    System.out.println("API Key starts with: " + (apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "NULL"));
    System.out.println("API URL: " + OPENAI_API_URL);

    try {
      // Convert image to base64
      System.out.println("Converting image to base64...");
      String base64Image = encodeImageToBase64(file);
      System.out.println("Base64 conversion successful, length: " + base64Image.length());

      // Create the request payload
      System.out.println("Creating request body...");
      Map<String, Object> requestBody = createRequestBody(base64Image);
      System.out.println("Request body created");

      // Set headers
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);
      System.out.println("Headers set with Bearer token");

      // Make the API call
      HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
      System.out.println("Making API call to OpenAI...");

      ResponseEntity<Map> response = restTemplate.postForEntity(OPENAI_API_URL, entity, Map.class);
      System.out.println("Response status: " + response.getStatusCode());
      System.out.println("Response body: " + response.getBody());

      String analysis = extractAnalysisFromResponse(response.getBody());
      System.out.println("Analysis extracted successfully");
      System.out.println("Analysis preview: " + analysis.substring(0, Math.min(100, analysis.length())) + "...");
      System.out.println("=== OPENAI DEBUG END (SUCCESS) ===");

      return analysis;

    } catch (Exception e) {
      System.out.println("=== OPENAI ERROR ===");
      System.out.println("Error type: " + e.getClass().getName());
      System.out.println("Error message: " + e.getMessage());
      System.out.println("Full stack trace:");
      e.printStackTrace();
      System.out.println("=== FALLING BACK TO MOCK ===");

      // Fall back to mock if API fails
      return generateMockAnalysis(file.getOriginalFilename());
    }
  }

  private String encodeImageToBase64(MultipartFile file) throws Exception {
    byte[] imageBytes = file.getBytes();
    return Base64.getEncoder().encodeToString(imageBytes);
  }

  private Map<String, Object> createRequestBody(String base64Image) {
    return Map.of(
            "model", "gpt-4o",
            "messages", List.of(
                    Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of(
                                            "type", "text",
                                            "text", "Analyze this image and describe its mood, atmosphere, and emotional tone in detail. Then suggest what type of music would match this scene. Focus on the energy level (calm to energetic), emotional valence (sad to happy), and overall vibe. Be specific about musical characteristics that would complement this image."
                                    ),
                                    Map.of(
                                            "type", "image_url",
                                            "image_url", Map.of(
                                                    "url", "data:image/jpeg;base64," + base64Image
                                            )
                                    )
                            )
                    )
            ),
            "max_tokens", 300
    );
  }

  private String extractAnalysisFromResponse(Map<String, Object> response) {
    try {
      List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
      Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
      return (String) message.get("content");
    } catch (Exception e) {
      System.out.println("Error parsing OpenAI response: " + e.getMessage());
      return "Error parsing OpenAI response: " + e.getMessage();
    }
  }

  // Fallback mock analysis if OpenAI fails
  private String generateMockAnalysis(String filename) {
    System.out.println("Using mock analysis fallback for: " + filename);

    String lowerFilename = filename != null ? filename.toLowerCase() : "";

    if (lowerFilename.contains("sunset") || lowerFilename.contains("golden")) {
      return "This image captures a warm, golden hour scene with soft, romantic lighting. The atmosphere is peaceful and contemplative, suggesting music with gentle melodies, acoustic elements, or ambient soundscapes that evoke tranquility and warmth.";
    } else if (lowerFilename.contains("party") || lowerFilename.contains("concert")) {
      return "This image shows a vibrant, high-energy scene full of movement and excitement. The mood is energetic and celebratory, perfect for upbeat tracks with strong rhythms, electronic beats, or danceable pop music that matches this lively atmosphere.";
    } else if (lowerFilename.contains("night") || lowerFilename.contains("dark")) {
      return "This image has a dramatic, moody atmosphere with deep contrasts and mysterious elements. The scene calls for intense, atmospheric music - perhaps alternative rock, cinematic scores, or electronic music with darker undertones.";
    } else {
      return "This image has a moderate energy level with balanced emotional tone. The atmosphere suggests music with steady rhythm and harmonious melodies - perhaps indie folk, ambient electronic, or contemporary instrumental tracks would complement this scene well.";
    }
  }
}