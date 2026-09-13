package com.example.distributed_rate_limiter.api;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rate-limit")
public class RateLimitController {

    private final RateLimiter rateLimiter;

    public RateLimitController(
            RateLimiter rateLimiter
    ) {
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/check")
    public ResponseEntity<RateLimitResponse> check(
            @RequestBody RateLimitRequest request
    ) {

        RateLimitResult result =
                rateLimiter.tryAcquire(request.key());

        RateLimitResponse response =
                RateLimitResponse.from(result);

        /*
         * Request was allowed.
         */
        if (result.allowed()) {
            return ResponseEntity
                    .ok()
                    .body(response);
        }

        /*
         * Request was rejected.
         *
         * Retry-After tells the client how many
         * seconds it should wait before retrying.
         */
        return ResponseEntity
                .status(429)
                .header(
                        "Retry-After",
                        String.valueOf(
                                result.retryAfter()
                        )
                )
                .body(response);
    }
}