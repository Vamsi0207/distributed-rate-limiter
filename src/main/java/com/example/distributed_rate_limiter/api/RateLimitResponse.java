package com.example.distributed_rate_limiter.api;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;

public record RateLimitResponse(
        boolean allowed,
        long remaining,
        long retryAfter
) {

    public static RateLimitResponse from(
            RateLimitResult result
    ) {
        return new RateLimitResponse(
                result.allowed(),
                result.remaining(),
                result.retryAfter()
        );
    }
}