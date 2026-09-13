package com.example.distributed_rate_limiter.integration;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RateLimiterIntegrationTest {

    private static final String TEST_KEY_PREFIX =
            "integration-";

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanTestData() {

        var keys =
                redisTemplate.keys(
                        "rate-limit:fixed:" +
                                TEST_KEY_PREFIX +
                                "*"
                );

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldAllowRequestsUntilLimitIsReached() {

        String key =
                TEST_KEY_PREFIX +
                        "user-" +
                        System.nanoTime();

        for (int i = 0; i < 5; i++) {

            RateLimitResult result =
                    rateLimiter.tryAcquire(key);

            assertTrue(result.allowed());

            assertEquals(
                    4 - i,
                    result.remaining()
            );
        }

        RateLimitResult rejected =
                rateLimiter.tryAcquire(key);

        assertFalse(
                rejected.allowed()
        );

        assertEquals(
                0,
                rejected.remaining()
        );
    }

    @Test
    void shouldStoreRateLimitStateInRedis() {

        String key =
                TEST_KEY_PREFIX +
                        "redis-" +
                        System.nanoTime();

        rateLimiter.tryAcquire(key);

        var keys =
                redisTemplate.keys(
                        "rate-limit:fixed:" +
                                key +
                                ":*"
                );

        assertNotNull(keys);

        assertEquals(
                1,
                keys.size()
        );

        String redisKey =
                keys.iterator()
                        .next();

        String value =
                redisTemplate
                        .opsForValue()
                        .get(redisKey);

        assertEquals(
                "1",
                value
        );
    }
}