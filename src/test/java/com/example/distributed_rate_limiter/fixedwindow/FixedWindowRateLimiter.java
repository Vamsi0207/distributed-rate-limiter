package com.example.distributed_rate_limiter.fixedwindow;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.fixedwindow.FixedWindowRateLimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class FixedWindowRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private FixedWindowRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {

        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(
                        "localhost",
                        6379
                );

        connectionFactory.afterPropertiesSet();

        redisTemplate =
                new StringRedisTemplate(connectionFactory);

        rateLimiter =
                new FixedWindowRateLimiter(
                        redisTemplate,
                        5,
                        Duration.ofSeconds(60)
                );
    }

    @Test
    void shouldAllowRequestsUntilLimitIsReached() {

        String key =
                "test-user-basic-" + System.nanoTime();

        for (int i = 1; i <= 5; i++) {

            RateLimitResult result =
                    rateLimiter.tryAcquire(key);

            assertTrue(result.allowed());

            assertEquals(
                    5 - i,
                    result.remaining()
            );

            assertEquals(
                    0,
                    result.retryAfter()
            );
        }

        RateLimitResult rejected =
                rateLimiter.tryAcquire(key);

        assertFalse(rejected.allowed());

        assertEquals(
                0,
                rejected.remaining()
        );

        assertTrue(
                rejected.retryAfter() > 0
        );
    }

    @Test
    void shouldNotExceedLimitUnderConcurrency()
            throws Exception {

        String key =
                "test-user-concurrent-" + System.nanoTime();

        int numberOfRequests = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(20);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<RateLimitResult>> futures =
                new ArrayList<>();

        for (int i = 0;
             i < numberOfRequests;
             i++) {

            futures.add(
                    executor.submit(() -> {

                        startLatch.await();

                        return rateLimiter.tryAcquire(key);
                    })
            );
        }

        startLatch.countDown();

        int allowed = 0;

        for (Future<RateLimitResult> future :
                futures) {

            RateLimitResult result =
                    future.get();

            if (result.allowed()) {
                allowed++;
            }
        }

        executor.shutdown();

        assertEquals(
                5,
                allowed
        );
    }

    @Test
    void shouldAllowRequestsAgainAfterWindowExpires() {

        String key =
                "test-user-expiration-" + System.nanoTime();

        /*
         * Freeze time at a known instant.
         */
        Instant start =
                Instant.parse(
                        "2026-09-13T10:00:00Z"
                );

        Clock clock =
                Clock.fixed(
                        start,
                        ZoneOffset.UTC
                );

        /*
         * Limit = 2
         * Window = 60 seconds
         * Clock = fixed clock
         */
        FixedWindowRateLimiter testRateLimiter =
                new FixedWindowRateLimiter(
                        redisTemplate,
                        2,
                        Duration.ofSeconds(60),
                        clock
                );

        /*
         * First request.
         */
        RateLimitResult first =
                testRateLimiter.tryAcquire(key);

        assertTrue(first.allowed());

        assertEquals(
                1,
                first.remaining()
        );

        /*
         * Second request.
         */
        RateLimitResult second =
                testRateLimiter.tryAcquire(key);

        assertTrue(second.allowed());

        assertEquals(
                0,
                second.remaining()
        );

        /*
         * Third request should be rejected.
         *
         * The clock has not moved, so this
         * request is definitely in the same window.
         */
        RateLimitResult rejected =
                testRateLimiter.tryAcquire(key);

        assertFalse(rejected.allowed());

        assertEquals(
                0,
                rejected.remaining()
        );

        assertTrue(
                rejected.retryAfter() > 0
        );

        /*
         * Move the clock exactly 60 seconds
         * into the next fixed window.
         */
        Clock nextWindowClock =
                Clock.fixed(
                        start.plusSeconds(60),
                        ZoneOffset.UTC
                );

        /*
         * Create a limiter using the new clock.
         */
        FixedWindowRateLimiter nextWindowRateLimiter =
                new FixedWindowRateLimiter(
                        redisTemplate,
                        2,
                        Duration.ofSeconds(60),
                        nextWindowClock
                );

        /*
         * This request belongs to the new window.
         */
        RateLimitResult allowedAgain =
                nextWindowRateLimiter.tryAcquire(key);

        assertTrue(
                allowedAgain.allowed()
        );

        assertEquals(
                1,
                allowedAgain.remaining()
        );
    }
}