package com.fox.aikbassistant.model;

import java.time.Instant;

public record UploadAudit(
        String id,
        String uploaderEmail,
        String source,
        long size,
        String status,
        String message,
        Integer chunkCount,
        Instant createdAt) {}
