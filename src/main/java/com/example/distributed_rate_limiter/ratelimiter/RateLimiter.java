package com.example.distributed_rate_limiter.ratelimiter;

public interface RateLimiter {

    RateLimitResult tryAcquire(String key);
}