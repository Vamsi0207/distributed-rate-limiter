package com.example.distributed_rate_limiter.integration;

import com.example.distributed_rate_limiter.api.RateLimitRequest;
import com.example.distributed_rate_limiter.api.RateLimitResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RateLimitHttpIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final HttpClient httpClient =
            HttpClient.newHttpClient();

    private final JsonMapper jsonMapper =
            JsonMapper.builder()
                    .build();

    @LocalServerPort
    private int port;

    @BeforeEach
    void cleanRedis() {

        redisTemplate
                .getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    void shouldAllowRequestThroughHttp() throws Exception {

        String key =
                "http-user-" +
                        System.nanoTime();

        RateLimitResponse response =
                sendRequest(key);

        assertNotNull(response);

        assertTrue(
                response.allowed()
        );

        assertEquals(
                4,
                response.remaining()
        );

        assertEquals(
                0,
                response.retryAfter()
        );
    }

    @Test
    void shouldReturn429WhenRateLimitIsExceeded() throws Exception {

        String key =
                "http-limit-" +
                        System.nanoTime();

        /*
         * Consume all five requests.
         */
        for (int i = 0; i < 5; i++) {

            RateLimitHttpResponse response =
                    sendRequestWithStatus(key);

            assertEquals(
                    HttpStatus.OK,
                    response.getStatusCode()
            );

            assertNotNull(
                    response.getBody()
            );

            assertTrue(
                    response.getBody().allowed()
            );
        }

        /*
         * Sixth request must be rejected.
         */
        RateLimitHttpResponse response =
                sendRequestWithStatus(key);

        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                response.getStatusCode()
        );

        assertNotNull(
                response.getBody()
        );

        assertFalse(
                response.getBody().allowed()
        );

        assertEquals(
                0,
                response.getBody().remaining()
        );

        assertTrue(
                response.getBody().retryAfter() > 0
        );

        assertNotNull(
                response.getHeaders()
                        .getFirst("Retry-After")
        );
    }

    @Test
    void shouldKeepDifferentKeysIndependent() throws Exception {

        String userA =
                "http-user-a-" +
                        System.nanoTime();

        String userB =
                "http-user-b-" +
                        System.nanoTime();

        /*
         * Exhaust user A's limit.
         */
        for (int i = 0; i < 5; i++) {

            RateLimitHttpResponse response =
                    sendRequestWithStatus(userA);

            assertEquals(
                    HttpStatus.OK,
                    response.getStatusCode()
            );
        }

        /*
         * User A is now rejected.
         */
        RateLimitHttpResponse rejected =
                sendRequestWithStatus(userA);

        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                rejected.getStatusCode()
        );

        /*
         * User B has an independent limit.
         */
        RateLimitHttpResponse userBResponse =
                sendRequestWithStatus(userB);

        assertEquals(
                HttpStatus.OK,
                userBResponse.getStatusCode()
        );

        assertNotNull(
                userBResponse.getBody()
        );

        assertTrue(
                userBResponse.getBody().allowed()
        );

        assertEquals(
                4,
                userBResponse.getBody().remaining()
        );
    }

    private RateLimitResponse sendRequest(
            String key
    ) throws Exception {

        RateLimitHttpResponse response =
                sendRequestWithStatus(key);

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode()
        );

        return response.getBody();
    }

    private RateLimitHttpResponse sendRequestWithStatus(
            String key
    ) throws Exception {

        String url =
                "http://localhost:" +
                        port +
                        "/api/v1/rate-limit/check";

        RateLimitRequest request =
                new RateLimitRequest(key);

        String body =
                jsonMapper.writeValueAsString(request);

        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header(
                                HttpHeaders.CONTENT_TYPE,
                                MediaType.APPLICATION_JSON_VALUE
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(body)
                        )
                        .build();

        HttpResponse<String> httpResponse =
                httpClient.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofString()
                );

        RateLimitResponse response =
                jsonMapper.readValue(
                        httpResponse.body(),
                        RateLimitResponse.class
                );

        HttpHeaders responseHeaders =
                new HttpHeaders();

        httpResponse
                .headers()
                .map()
                .forEach(responseHeaders::put);

        return new RateLimitHttpResponse(
                HttpStatusCode.valueOf(
                        httpResponse.statusCode()
                ),
                response,
                responseHeaders
        );
    }

    private record RateLimitHttpResponse(
            HttpStatusCode statusCode,
            RateLimitResponse body,
            HttpHeaders headers
    ) {

        HttpStatusCode getStatusCode() {
            return statusCode;
        }

        RateLimitResponse getBody() {
            return body;
        }

        HttpHeaders getHeaders() {
            return headers;
        }
    }
}
