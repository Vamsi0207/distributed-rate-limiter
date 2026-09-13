package com.example.distributed_rate_limiter.ratelimiter;

public record RateLimitResult(
        boolean allowed,
        long remaining,
        long retryAfter
) {
}