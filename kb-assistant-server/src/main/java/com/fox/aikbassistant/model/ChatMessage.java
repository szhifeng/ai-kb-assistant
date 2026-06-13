package com.fox.aikbassistant.model;

import java.time.Instant;
import java.util.List;

public record ChatMessage(
        String id,
        String role,
        String content,
        String reasoning,
        List<Citation> citations,
        Instant createdAt,
        String mode,
        boolean webSearchEnabled,
        String error) {}
