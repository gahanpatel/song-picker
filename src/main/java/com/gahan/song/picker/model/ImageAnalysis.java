package com.gahan.song.picker.model;

import java.util.Map;

public class ImageAnalysis {
    public final String text;
    public final Map<String, Double> moodProfile;

    public ImageAnalysis(String text, Map<String, Double> moodProfile) {
        this.text = text;
        this.moodProfile = moodProfile;
    }
}
