package com.fox.aikbassistant.model;

import java.time.Instant;

public record DocumentDetail(
        String source,
        long chunkCount,
        Long size,
        String uploaderEmail,
        String status,
        String message,
        Instant uploadedAt) {}
