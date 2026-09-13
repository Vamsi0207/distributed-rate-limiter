package com.example.distributed_rate_limiter.tokenbucket;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.tokenbucket.TokenBucketRateLimiter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

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

class TokenBucketRateLimiterTest {

    private static LettuceConnectionFactory connectionFactory;

    private static StringRedisTemplate redisTemplate;

    private TokenBucketRateLimiter rateLimiter;

    @BeforeAll
    static void setUpRedis() {

        connectionFactory =
                new LettuceConnectionFactory(
                        "localhost",
                        6379
                );

        connectionFactory.afterPropertiesSet();

        redisTemplate =
                new StringRedisTemplate(
                        connectionFactory
                );
    }

    @AfterAll
    static void tearDownRedis() {

        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {

        rateLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1
                );
    }

    @Test
    void shouldAllowBurstUpToCapacity() {

        String key =
                "test-token-burst-" + System.nanoTime();

        Instant start =
                Instant.parse(
                        "2026-09-13T10:00:00Z"
                );

        Clock fixedClock =
                Clock.fixed(
                        start,
                        ZoneOffset.UTC
                );

        TokenBucketRateLimiter burstLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        fixedClock
                );

        for (int i = 1; i <= 5; i++) {

            RateLimitResult result =
                    burstLimiter.tryAcquire(key);

            assertTrue(
                    result.allowed()
            );

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
                burstLimiter.tryAcquire(key);

        assertFalse(
                rejected.allowed()
        );

        assertEquals(
                0,
                rejected.remaining()
        );

        assertTrue(
                rejected.retryAfter() > 0
        );
    }

    @Test
    void shouldRefillTokensOverTime() {

        String key =
                "test-token-refill-" + System.nanoTime();

        Instant start =
                Instant.parse(
                        "2026-09-13T10:00:00Z"
                );

        Clock initialClock =
                Clock.fixed(
                        start,
                        ZoneOffset.UTC
                );

        TokenBucketRateLimiter initialLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        initialClock
                );

        for (int i = 0; i < 5; i++) {

            RateLimitResult result =
                    initialLimiter.tryAcquire(key);

            assertTrue(
                    result.allowed()
            );
        }

        RateLimitResult rejected =
                initialLimiter.tryAcquire(key);

        assertFalse(
                rejected.allowed()
        );

        Clock afterThreeSeconds =
                Clock.fixed(
                        start.plusSeconds(3),
                        ZoneOffset.UTC
                );

        TokenBucketRateLimiter refilledLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        afterThreeSeconds
                );

        for (int i = 0; i < 3; i++) {

            RateLimitResult result =
                    refilledLimiter.tryAcquire(key);

            assertTrue(
                    result.allowed()
            );

            assertEquals(
                    2 - i,
                    result.remaining()
            );
        }

        RateLimitResult rejectedAgain =
                refilledLimiter.tryAcquire(key);

        assertFalse(
                rejectedAgain.allowed()
        );
    }

    @Test
    void shouldNotExceedAvailableTokensUnderConcurrency()
            throws Exception {

        String key =
                "test-token-concurrent-" + System.nanoTime();

        Instant start =
                Instant.parse(
                        "2026-09-13T10:00:00Z"
                );

        Clock fixedClock =
                Clock.fixed(
                        start,
                        ZoneOffset.UTC
                );

        TokenBucketRateLimiter concurrentRateLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        fixedClock
                );

        int numberOfRequests = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(20);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<RateLimitResult>> futures =
                new ArrayList<>();

        try {

            for (int i = 0;
                 i < numberOfRequests;
                 i++) {

                futures.add(
                        executor.submit(() -> {

                            startLatch.await();

                            return concurrentRateLimiter
                                    .tryAcquire(key);
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

            assertEquals(
                    5,
                    allowed
            );

        } finally {

            executor.shutdownNow();
        }
    }

    @Test
    void shouldMaintainIndependentBucketsForDifferentKeys() {

        String userA =
                "test-user-a-" + System.nanoTime();

        String userB =
                "test-user-b-" + System.nanoTime();

        Instant start =
                Instant.parse(
                        "2026-09-13T10:00:00Z"
                );

        Clock fixedClock =
                Clock.fixed(
                        start,
                        ZoneOffset.UTC
                );

        TokenBucketRateLimiter fixedRateLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        fixedClock
                );

        /*
         * Consume all tokens from user A.
         */
        for (int i = 0; i < 5; i++) {

            RateLimitResult result =
                    fixedRateLimiter.tryAcquire(userA);

            assertTrue(
                    result.allowed()
            );

            assertEquals(
                    4 - i,
                    result.remaining()
            );
        }

        /*
         * User A should now be rate limited.
         */
        assertFalse(
                fixedRateLimiter
                        .tryAcquire(userA)
                        .allowed()
        );

        /*
         * User B has an independent bucket.
         *
         * User B should still have all
         * five tokens available.
         */
        for (int i = 0; i < 5; i++) {

            RateLimitResult result =
                    fixedRateLimiter.tryAcquire(userB);

            assertTrue(
                    result.allowed()
            );

            assertEquals(
                    4 - i,
                    result.remaining()
            );
        }
    }

    @Test
    void shouldRejectInvalidCapacity() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(
                        redisTemplate,
                        0,
                        1
                )
        );
    }

    @Test
    void shouldRejectNegativeCapacity() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(
                        redisTemplate,
                        -1,
                        1
                )
        );
    }

    @Test
    void shouldRejectInvalidRefillRate() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        0
                )
        );
    }

    @Test
    void shouldSetTtlOnBucketState() {

        String key =
                "test-token-ttl-" + System.nanoTime();

        TokenBucketRateLimiter ttlLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        Duration.ofSeconds(10)
                );

        RateLimitResult result =
                ttlLimiter.tryAcquire(key);

        assertTrue(
                result.allowed()
        );

        String redisKey =
                "rate-limit:token:" + key;

        Long ttl =
                redisTemplate.getExpire(redisKey);

        assertNotNull(ttl);

        assertTrue(
                ttl > 0 && ttl <= 10
        );
    }

    @Test
    void shouldRefreshTtlWhenBucketIsUsed()
            throws Exception {

        String key =
                "test-token-ttl-refresh-" +
                        System.nanoTime();

        TokenBucketRateLimiter ttlLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        Duration.ofSeconds(10)
                );

        ttlLimiter.tryAcquire(key);

        String redisKey =
                "rate-limit:token:" + key;

        Long initialTtl =
                redisTemplate.getExpire(redisKey);

        assertNotNull(initialTtl);

        assertTrue(
                initialTtl > 0
        );

        Thread.sleep(2000);

        Long reducedTtl =
                redisTemplate.getExpire(redisKey);

        assertNotNull(reducedTtl);

        assertTrue(
                reducedTtl < initialTtl
        );

        /*
         * Use the bucket again.
         *
         * The Lua script should refresh
         * the expiration time.
         */
        ttlLimiter.tryAcquire(key);

        Long refreshedTtl =
                redisTemplate.getExpire(redisKey);

        assertNotNull(refreshedTtl);

        assertTrue(
                refreshedTtl > reducedTtl
        );

        assertTrue(
                refreshedTtl <= 10
        );
    }

    @Test
    void shouldExpireInactiveBucket()
            throws Exception {

        String key =
                "test-token-expiration-" +
                        System.nanoTime();

        TokenBucketRateLimiter ttlLimiter =
                new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        Duration.ofSeconds(2)
                );

        ttlLimiter.tryAcquire(key);

        String redisKey =
                "rate-limit:token:" + key;

        assertTrue(
                redisTemplate.hasKey(redisKey)
        );

        /*
         * Wait until the Redis key expires.
         */
        Thread.sleep(3000);

        assertFalse(
                redisTemplate.hasKey(redisKey)
        );
    }

    @Test
    void shouldRejectInvalidStateTtl() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(
                        redisTemplate,
                        5,
                        1,
                        Duration.ZERO
                )
        );
    }
}