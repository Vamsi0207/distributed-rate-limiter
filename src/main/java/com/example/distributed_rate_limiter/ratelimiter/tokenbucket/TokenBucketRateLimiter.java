package com.example.distributed_rate_limiter.ratelimiter.tokenbucket;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class TokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    private final long capacity;

    private final long refillRate;

    private final Duration stateTtl;

    private final Clock clock;

    private final DefaultRedisScript<List> script;

    /*
     * Production constructor.
     *
     * Default TTL = 60 seconds.
     */
    public TokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            long capacity,
            long refillRate
    ) {
        this(
                redisTemplate,
                capacity,
                refillRate,
                Duration.ofSeconds(60),
                Clock.systemUTC()
        );
    }

    /*
     * Constructor with custom TTL.
     *
     * Useful when TTL needs to be configured
     * explicitly.
     */
    public TokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            long capacity,
            long refillRate,
            Duration stateTtl
    ) {
        this(
                redisTemplate,
                capacity,
                refillRate,
                stateTtl,
                Clock.systemUTC()
        );
    }

    /*
     * Constructor with custom Clock.
     *
     * Primarily useful for deterministic tests.
     *
     * Uses the default TTL of 60 seconds.
     */
    public TokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            long capacity,
            long refillRate,
            Clock clock
    ) {
        this(
                redisTemplate,
                capacity,
                refillRate,
                Duration.ofSeconds(60),
                clock
        );
    }

    /*
     * Fully configurable constructor.
     *
     * Allows tests to control both:
     *
     * - state TTL
     * - current time
     */
    public TokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            long capacity,
            long refillRate,
            Duration stateTtl,
            Clock clock
    ) {

        if (redisTemplate == null) {
            throw new IllegalArgumentException(
                    "redisTemplate must not be null"
            );
        }

        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be greater than 0"
            );
        }

        if (refillRate <= 0) {
            throw new IllegalArgumentException(
                    "refillRate must be greater than 0"
            );
        }

        if (stateTtl == null ||
                stateTtl.isZero() ||
                stateTtl.isNegative()) {

            throw new IllegalArgumentException(
                    "stateTtl must be greater than 0"
            );
        }

        /*
         * Redis EXPIRE works with whole seconds.
         */
        if (stateTtl.getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "stateTtl must be at least 1 second"
            );
        }

        if (clock == null) {
            throw new IllegalArgumentException(
                    "clock must not be null"
            );
        }

        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.stateTtl = stateTtl;
        this.clock = clock;

        this.script = new DefaultRedisScript<>();

        this.script.setLocation(
                new ClassPathResource(
                        "scripts/token_bucket.lua"
                )
        );

        this.script.setResultType(List.class);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "key must not be null or blank"
            );
        }

        /*
         * Use epoch seconds because the Lua script
         * performs refill calculations in seconds.
         */
        long currentTime =
                Instant.now(clock).getEpochSecond();

        String redisKey =
                "rate-limit:token:" + key;

        /*
         * Lua arguments:
         *
         * ARGV[1] = capacity
         * ARGV[2] = refill rate
         * ARGV[3] = current time
         * ARGV[4] = state TTL
         */
        List<?> result =
                redisTemplate.execute(
                        script,
                        List.of(redisKey),
                        String.valueOf(capacity),
                        String.valueOf(refillRate),
                        String.valueOf(currentTime),
                        String.valueOf(
                                stateTtl.getSeconds()
                        )
                );

        if (result == null || result.size() != 3) {
            throw new IllegalStateException(
                    "Unexpected response from token bucket Lua script"
            );
        }

        /*
         * Lua returns:
         *
         * {allowed, remaining, retryAfter}
         */
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