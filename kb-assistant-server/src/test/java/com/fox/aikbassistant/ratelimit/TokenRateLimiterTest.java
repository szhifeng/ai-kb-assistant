package com.fox.aikbassistant.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenRateLimiterTest {

    @Test
    void tryAcquire_returnsFalseWhenOverLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("kb:tokens:c1")).thenReturn("99000");

        var props = new RateLimitProperties(60, 100000, "reject");
        var limiter = new TokenRateLimiter(redis, props);

        assertThat(limiter.tryAcquire("c1", 2000)).isFalse();
        assertThat(limiter.tryAcquire("c1", 500)).isTrue();
    }

    @Test
    void record_incrementsCounter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("kb:tokens:c2", 1000)).thenReturn(1000L);

        var props = new RateLimitProperties(60, 100000, "reject");
        var limiter = new TokenRateLimiter(redis, props);

        limiter.record("c2", 1000);

        verify(ops).increment("kb:tokens:c2", 1000);
    }
}
