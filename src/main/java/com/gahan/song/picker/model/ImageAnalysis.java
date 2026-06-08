package com.gahan.song.picker.model;

import java.util.List;
import java.util.Map;

public class ImageAnalysis {
    public final String text;
    public final Map<String, Double> moodProfile;
    public final List<Map<String, String>> suggestedSongs;

    public ImageAnalysis(String text, Map<String, Double> moodProfile) {
        this(text, moodProfile, List.of());
    }

    public ImageAnalysis(String text, Map<String, Double> moodProfile, List<Map<String, String>> suggestedSongs) {
        this.text = text;
        this.moodProfile = moodProfile;
        this.suggestedSongs = suggestedSongs != null ? suggestedSongs : List.of();
    }
}
