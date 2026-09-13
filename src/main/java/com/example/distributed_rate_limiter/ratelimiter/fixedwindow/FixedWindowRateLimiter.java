package com.example.distributed_rate_limiter.ratelimiter.fixedwindow;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class FixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    private final long limit;

    private final Duration window;

    private final Clock clock;

    private final DefaultRedisScript<List> script;

    public FixedWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            long limit,
            Duration window
    ) {
        this(
                redisTemplate,
                limit,
                window,
                Clock.systemUTC()
        );
    }

    public FixedWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            long limit,
            Duration window,
            Clock clock
    ) {

        if (redisTemplate == null) {
            throw new IllegalArgumentException(
                    "redisTemplate must not be null"
            );
        }

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit must be greater than 0"
            );
        }

        if (window == null ||
                window.isZero() ||
                window.isNegative()) {

            throw new IllegalArgumentException(
                    "window must be greater than 0"
            );
        }

        if (window.getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "window must be at least 1 second"
            );
        }

        if (clock == null) {
            throw new IllegalArgumentException(
                    "clock must not be null"
            );
        }

        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.window = window;
        this.clock = clock;

        this.script =
                new DefaultRedisScript<>();

        this.script.setLocation(
                new ClassPathResource(
                        "scripts/fixed_window.lua"
                )
        );

        this.script.setResultType(
                List.class
        );
    }

    @Override
    public RateLimitResult tryAcquire(String key) {

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "key must not be null or blank"
            );
        }

        long currentTime =
                Instant.now(clock)
                        .getEpochSecond();

        long windowSeconds =
                window.getSeconds();

        long windowId =
                currentTime / windowSeconds;

        String redisKey =
                "rate-limit:fixed:" +
                        key +
                        ":" +
                        windowId;

        long windowEnd =
                (windowId + 1) *
                        windowSeconds;

        long ttl =
                windowEnd - currentTime;

        List<?> result =
                redisTemplate.execute(
                        script,
                        List.of(redisKey),
                        String.valueOf(limit),
                        String.valueOf(ttl)
                );

        if (result == null ||
                result.size() != 3) {

            throw new IllegalStateException(
                    "Unexpected response from " +
                            "fixed window Lua script"
            );
        }

        boolean allowed =
                ((Number) result.get(0))
                        .longValue() == 1;

        long remaining =
                ((Number) result.get(1))
                        .longValue();

        long retryAfter =
                ((Number) result.get(2))
                        .longValue();

        return new RateLimitResult(
                allowed,
                remaining,
                retryAfter
        );
    }
}