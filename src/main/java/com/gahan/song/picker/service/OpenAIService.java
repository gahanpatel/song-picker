package com.gahan.song.picker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahan.song.picker.model.ImageAnalysis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Service
public class OpenAIService {

  @Value("${openai.api.key}")
  private String apiKey;

  private final RestTemplate restTemplate = new RestTemplate();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
  private static final List<String> MOOD_KEYS = List.of("energy", "valence", "danceability", "acousticness", "tempo");

  public ImageAnalysis analyzeImage(MultipartFile file) throws Exception {
    try {
      String base64Image = Base64.getEncoder().encodeToString(file.getBytes());
      String mimeType = (file.getContentType() != null && file.getContentType().startsWith("image/"))
              ? file.getContentType() : "image/jpeg";

      Map<String, Object> requestBody = createRequestBody(base64Image, mimeType);

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      ResponseEntity<Map> response = restTemplate.postForEntity(
              OPENAI_API_URL, new HttpEntity<>(requestBody, headers), Map.class);

      return parseResponse(response.getBody());

    } catch (Exception e) {
      System.out.println("OpenAI call failed, using mock: " + e.getMessage());
      return generateMockAnalysis(file.getOriginalFilename());
    }
  }

  private Map<String, Object> createRequestBody(String base64Image, String mimeType) {
    String prompt = """
            Analyze this image for its mood and emotional tone. Return ONLY a JSON object with these fields:
            - "explanation": 2-3 sentences describing the mood, atmosphere, and emotional tone
            - "energy": 0.0-1.0 (0=very calm/still, 1=very intense/energetic)
            - "valence": 0.0-1.0 (0=dark/sad/tense, 1=bright/happy/positive)
            - "danceability": 0.0-1.0 (0=still/contemplative, 1=rhythmic/danceable)
            - "acousticness": 0.0-1.0 (0=urban/electronic/synthetic feel, 1=natural/organic/acoustic feel)
            - "tempo": 0.0-1.0 (0=very slow/static, 1=very fast-paced/frantic)
            Return only valid JSON with no markdown formatting or code blocks.
            """;

    return Map.of(
            "model", "gpt-4o",
            "messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "text", "text", prompt),
                            Map.of("type", "image_url", "image_url", Map.of(
                                    "url", "data:" + mimeType + ";base64," + base64Image
                            ))
                    )
            )),
            "max_tokens", 400
    );
  }

  private ImageAnalysis parseResponse(Map<String, Object> response) {
    try {
      List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
      String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

      // Strip markdown code fences if GPT wraps the JSON anyway
      content = content.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();

      Map<String, Object> parsed = objectMapper.readValue(content, Map.class);

      String explanation = (String) parsed.getOrDefault("explanation", content);
      Map<String, Double> moodProfile = new HashMap<>();
      for (String key : MOOD_KEYS) {
        Object val = parsed.get(key);
        if (val instanceof Number) moodProfile.put(key, ((Number) val).doubleValue());
      }

      return new ImageAnalysis(explanation, moodProfile);

    } catch (Exception e) {
      System.out.println("Failed to parse OpenAI JSON response: " + e.getMessage());
      return new ImageAnalysis("Could not parse image analysis.", Map.of());
    }
  }

  private ImageAnalysis generateMockAnalysis(String filename) {
    String lower = filename != null ? filename.toLowerCase() : "";

    if (lower.contains("sunset") || lower.contains("golden")) {
      return new ImageAnalysis(
              "A warm golden-hour scene with soft romantic lighting and a peaceful, contemplative atmosphere.",
              Map.of("energy", 0.25, "valence", 0.7, "danceability", 0.2, "acousticness", 0.8, "tempo", 0.2)
      );
    } else if (lower.contains("party") || lower.contains("concert")) {
      return new ImageAnalysis(
              "A vibrant high-energy scene full of movement and excitement with a celebratory atmosphere.",
              Map.of("energy", 0.9, "valence", 0.85, "danceability", 0.85, "acousticness", 0.1, "tempo", 0.8)
      );
    } else if (lower.contains("night") || lower.contains("dark")) {
      return new ImageAnalysis(
              "A dramatic moody scene with deep contrasts and mysterious atmospheric elements.",
              Map.of("energy", 0.65, "valence", 0.25, "danceability", 0.35, "acousticness", 0.2, "tempo", 0.5)
      );
    } else {
      return new ImageAnalysis(
              "A balanced scene with moderate energy and harmonious emotional tone.",
              Map.of("energy", 0.5, "valence", 0.5, "danceability", 0.5, "acousticness", 0.5, "tempo", 0.5)
      );
    }
  }
}
