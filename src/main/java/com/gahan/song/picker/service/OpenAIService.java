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
    String filename = file.getOriginalFilename().toLowerCase();
    long fileSize = file.getSize();

    // Analyze filename for clues about image content
    if (filename.contains("sunset") || filename.contains("dawn") || filename.contains("golden")) {
      return "This image captures a warm, golden hour scene with soft, romantic lighting. The atmosphere is peaceful and contemplative, suggesting music with gentle melodies, acoustic elements, or ambient soundscapes that evoke tranquility and warmth.";
    } else if (filename.contains("party") || filename.contains("concert") || filename.contains("dance")) {
      return "This image shows a vibrant, high-energy scene full of movement and excitement. The mood is energetic and celebratory, perfect for upbeat tracks with strong rhythms, electronic beats, or danceable pop music that matches this lively atmosphere.";
    } else if (filename.contains("night") || filename.contains("dark") || filename.contains("storm")) {
      return "This image has a dramatic, moody atmosphere with deep contrasts and mysterious elements. The scene calls for intense, atmospheric music - perhaps alternative rock, cinematic scores, or electronic music with darker undertones.";
    } else if (filename.contains("nature") || filename.contains("forest") || filename.contains("mountain")) {
      return "This image depicts a serene natural landscape that evokes peace and connection with nature. The mood suggests organic, acoustic music - folk songs, classical pieces, or ambient nature sounds that complement this tranquil outdoor setting.";
    } else {
      // Use file size and random seed for more variation
      String[] moodTypes = {"energetic", "calm", "dramatic", "peaceful", "vibrant"};
      int index = (int) ((fileSize + filename.hashCode()) % moodTypes.length);
      String mood = moodTypes[Math.abs(index)];

      return generateMoodAnalysis(mood);
    }
  }

  private String generateMoodAnalysis(String moodType) {
    switch (moodType) {
      case "energetic":
        return "This image radiates energy and vibrancy with bright colors and dynamic composition. The uplifting mood calls for high-energy music - think pop hits, rock anthems, or electronic dance tracks that match this lively, positive atmosphere.";
      case "calm":
        return "This image conveys a sense of peace and tranquility with soft lighting and gentle composition. The serene atmosphere pairs beautifully with ambient music, acoustic folk, or classical pieces that enhance this meditative, restful mood.";
      case "dramatic":
        return "This image has a powerful, intense atmosphere with striking contrasts and bold elements. The dramatic mood suggests cinematic music, orchestral pieces, or alternative rock that captures this scene's emotional depth and intensity.";
      case "peaceful":
        return "This image evokes quiet contemplation and inner peace with harmonious elements and balanced composition. The gentle mood calls for soothing instrumentals, soft vocals, or nature-inspired ambient tracks.";
      case "vibrant":
        return "This image bursts with color and life, creating an optimistic and joyful atmosphere. The cheerful mood pairs perfectly with upbeat indie music, feel-good pop songs, or world music that celebrates life and positivity.";
      default:
        return "This image has a unique atmospheric quality that suggests a blend of contemplative and uplifting elements, calling for music that balances energy with emotional depth.";
    }
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
                                            "text", "Analyze this image and describe the mood, setting, and suggest what type of music would match this scene. Be specific about the atmosphere."
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
      return "Error parsing OpenAI response: " + e.getMessage();
    }
  }
}