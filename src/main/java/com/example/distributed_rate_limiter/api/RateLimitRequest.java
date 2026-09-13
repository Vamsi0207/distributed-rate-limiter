package com.example.distributed_rate_limiter.api;

public record RateLimitRequest(
        String key
) {
}