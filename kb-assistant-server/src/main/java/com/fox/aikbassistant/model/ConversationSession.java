package com.fox.aikbassistant.model;

import java.time.Instant;

public record ConversationSession(String id, String title, Instant createdAt, Instant updatedAt, long messageCount) {}
