package com.fox.aikbassistant.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TokenRateLimiter {

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public TokenRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean tryAcquire(String conversationId, long estimatedTokens) {
        String key = key(conversationId);
        String current = redis.opsForValue().get(key);
        long usedTokens = current == null ? 0 : Long.parseLong(current);
        return usedTokens + estimatedTokens <= properties.maxTokensPerWindow();
    }

    public void record(String conversationId, long tokens) {
        String key = key(conversationId);
        Long total = redis.opsForValue().increment(key, tokens);
        if (total != null && total == tokens) {
            redis.expire(key, Duration.ofSeconds(properties.windowSeconds()));
        }
    }

    private static String key(String conversationId) {
        String id = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
        return "kb:tokens:" + id;
    }
}
