package com.company.videouploadapi.mapper;

import com.company.videouploadapi.model.request.VideoData;
import com.fasterxml.jackson.databind.ObjectMapper;

public enum VideoDataMapper {
    VIDEO_DATA_MAPPER;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoData map(String message) {
        try {
            System.out.println(message);
            return objectMapper.readValue(message, VideoData.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse message: " + message, e);
        }
    }
}
