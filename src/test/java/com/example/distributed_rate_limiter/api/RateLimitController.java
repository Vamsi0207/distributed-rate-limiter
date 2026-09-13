package com.example.distributed_rate_limiter.api;

import com.example.distributed_rate_limiter.ratelimiter.RateLimitResult;
import com.example.distributed_rate_limiter.ratelimiter.RateLimiter;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RateLimitController.class)
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    void shouldReturnAllowedResponse() throws Exception {

        when(rateLimiter.tryAcquire(anyString()))
                .thenReturn(
                        new RateLimitResult(
                                true,
                                4,
                                0
                        )
                );

        mockMvc.perform(
                post("/api/v1/rate-limit/check")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                    "key": "user-123"
                                }
                                """)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.remaining").value(4))
        .andExpect(jsonPath("$.retryAfter").value(0))
        .andExpect(
                header().doesNotExist("Retry-After")
        );
    }

    @Test
    void shouldReturnTooManyRequestsWhenRateLimitExceeded()
            throws Exception {

        when(rateLimiter.tryAcquire(anyString()))
                .thenReturn(
                        new RateLimitResult(
                                false,
                                0,
                                12
                        )
                );

        mockMvc.perform(
                post("/api/v1/rate-limit/check")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                    "key": "user-123"
                                }
                                """)
        )
        .andExpect(status().isTooManyRequests())
        .andExpect(
                header()
                        .string(
                                "Retry-After",
                                "12"
                        )
        )
        .andExpect(
                jsonPath("$.allowed")
                        .value(false)
        )
        .andExpect(
                jsonPath("$.remaining")
                        .value(0)
        )
        .andExpect(
                jsonPath("$.retryAfter")
                        .value(12)
        );
    }
}