package com.example.distributed_rate_limiter.ratelimiter.fixedwindow;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

public class FixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final long limit;
    private final Duration window;

    public FixedWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            long limit,
            Duration window
    ) {
        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.window = window;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {

        long currentTime = Instant.now().getEpochSecond();
        long windowSeconds = window.getSeconds();

        long windowId = currentTime / windowSeconds;

        String redisKey = "rate-limit:fixed:" + key + ":" + windowId;

        String currentValue = redisTemplate.opsForValue().get(redisKey);

        long currentCount = currentValue == null
                ? 0
                : Long.parseLong(currentValue);

        if (currentCount >= limit) {

            long windowEnd = (windowId + 1) * windowSeconds;
            long retryAfter = windowEnd - currentTime;

            return new RateLimitResult(
                    false,
                    0,
                    retryAfter
            );
        }

        Long newCount = redisTemplate.opsForValue().increment(redisKey);

        if (newCount == 1) {
            long windowEnd = (windowId + 1) * windowSeconds;
            long ttl = windowEnd - currentTime;

            redisTemplate.expire(redisKey, Duration.ofSeconds(ttl));
        }

        long remaining = Math.max(0, limit - newCount);

        return new RateLimitResult(
                true,
                remaining,
                0
        );
    }
}