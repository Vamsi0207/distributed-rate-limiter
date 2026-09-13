package com.example.distributed_rate_limiter.config;

import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;
import com.example.distributed_rate_limiter.ratelimiter.fixedwindow.FixedWindowRateLimiter;
import com.example.distributed_rate_limiter.ratelimiter.tokenbucket.TokenBucketRateLimiter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RateLimiterConfig {

    private static final long FIXED_WINDOW_LIMIT = 5;

    private static final Duration FIXED_WINDOW_DURATION =
            Duration.ofSeconds(60);

    private static final long TOKEN_BUCKET_CAPACITY = 5;

    private static final long TOKEN_BUCKET_REFILL_RATE = 1;

    private static final Duration TOKEN_BUCKET_STATE_TTL =
            Duration.ofSeconds(60);

    @Bean
    public RateLimiter rateLimiter(
            StringRedisTemplate redisTemplate
    ) {

        return new FixedWindowRateLimiter(
                redisTemplate,
                FIXED_WINDOW_LIMIT,
                FIXED_WINDOW_DURATION
        );
    }

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(
            StringRedisTemplate redisTemplate
    ) {

        return new TokenBucketRateLimiter(
                redisTemplate,
                TOKEN_BUCKET_CAPACITY,
                TOKEN_BUCKET_REFILL_RATE,
                TOKEN_BUCKET_STATE_TTL
        );
    }
}