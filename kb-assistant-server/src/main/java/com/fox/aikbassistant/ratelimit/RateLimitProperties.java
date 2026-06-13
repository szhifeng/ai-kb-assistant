package com.fox.aikbassistant.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kb.ratelimit")
public record RateLimitProperties(
        long windowSeconds,
        long maxTokensPerWindow,
        String overflowStrategy) {

    public RateLimitProperties {
        if (windowSeconds <= 0) {
            windowSeconds = 60;
        }
        if (maxTokensPerWindow <= 0) {
            maxTokensPerWindow = 100_000;
        }
        if (overflowStrategy == null || overflowStrategy.isBlank()) {
            overflowStrategy = "reject";
        }
    }
}
