# Distributed Rate Limiter

A Redis-backed distributed rate limiter built with Java 21 and Spring Boot.

The system implements **Fixed Window** and **Token Bucket** rate-limiting algorithms using Redis Lua scripts for atomic state updates across concurrent requests.

## Why this project?

In a distributed application, maintaining rate-limit counters inside application memory is unreliable because each application instance has its own state.

```text
             ┌─────────────────┐
             │   Client        │
             └────────┬────────┘
                      │
                      ▼
             ┌─────────────────┐
             │ Rate Limit API  │
             └────────┬────────┘
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
   ┌──────────────┐        ┌──────────────┐
   │ Instance A   │        │ Instance B   │
   └──────┬───────┘        └──────┬───────┘
          │                       │
          └───────────┬───────────┘
                      ▼
              ┌─────────────┐
              │    Redis    │
              │  + Lua      │
              └─────────────┘
```

Redis provides shared state while Lua ensures that checking and modifying the rate-limit state happens atomically.

---

## Features

- Distributed rate limiting using Redis
- Fixed Window algorithm
- Token Bucket algorithm
- Atomic Redis Lua scripts
- Concurrent request handling
- Redis TTL-based state cleanup
- HTTP `429 Too Many Requests`
- `Retry-After` response header
- Deterministic time-based testing using `Clock`
- Spring Boot integration tests
- HTTP integration tests
- Docker-based Redis

---

## Technology Stack

- **Java 21**
- **Spring Boot 4.1.1**
- **Maven**
- **Spring Data Redis**
- **Redis**
- **Redis Lua**
- **Docker**
- **JUnit 5**

---

# Architecture

```text
                    HTTP Client
                         │
                         ▼
              ┌─────────────────────┐
              │  RateLimitController│
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │    RateLimiter      │
              │     Interface       │
              └──────────┬──────────┘
                         │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
      ┌───────────────┐     ┌───────────────┐
      │ Fixed Window  │     │ Token Bucket  │
      │ Rate Limiter  │     │ Rate Limiter  │
      └───────┬───────┘     └───────┬───────┘
              │                     │
              ▼                     ▼
        ┌────────────────────────────────┐
        │          Redis Lua              │
        │     Atomic State Operation      │
        └────────────────┬───────────────┘
                         │
                         ▼
                  ┌─────────────┐
                  │    Redis    │
                  └─────────────┘
```

The application does not maintain rate-limit state in JVM memory. Multiple application instances can therefore share the same Redis-backed state.

---

# Fixed Window

The Fixed Window algorithm divides time into fixed intervals.

Example configuration:

```text
Limit  = 5 requests
Window = 60 seconds
```

For every request, the application calculates:

```text
windowId = epochSeconds / windowSeconds
```

The Redis key is:

```text
rate-limit:fixed:<key>:<windowId>
```

Example:

```text
rate-limit:fixed:user-123:29384721
```

The Redis value represents the number of requests consumed in that window.

### Request flow

```text
Request
   │
   ▼
Calculate window ID
   │
   ▼
Build Redis key
   │
   ▼
Execute Lua script
   │
   ├── Limit available ──► Increment counter ──► Allow
   │
   └── Limit reached ─────────────────────────► Reject
```

### Advantages

- Simple implementation
- Low state complexity
- Efficient Redis operations
- Easy to reason about

### Limitation

Fixed Window can produce a burst around window boundaries.

For example, a client could make:

```text
5 requests near the end of Window 1
5 requests immediately after Window 2 begins
```

This is one reason the project also implements Token Bucket.

---

# Token Bucket

The Token Bucket algorithm models request capacity using tokens.

Current configuration:

```text
Capacity    = 5 tokens
Refill rate = 1 token / second
```

Each key maintains:

```text
tokens
last_refill_time
```

Redis key:

```text
rate-limit:token:<key>
```

### Refill calculation

When a request arrives:

```text
elapsed = currentTime - lastRefillTime

newTokens =
    min(
        capacity,
        currentTokens + elapsed * refillRate
    )
```

If at least one token is available:

```text
tokens = tokens - 1
```

and the request is allowed.

Otherwise the request is rejected.

### Example

With capacity `5`:

```text
Request 1 → Allowed
Request 2 → Allowed
Request 3 → Allowed
Request 4 → Allowed
Request 5 → Allowed
Request 6 → Rejected
```

After one second, one token becomes available again.

This allows controlled bursts while maintaining a sustainable request rate.

---

# Why Redis?

A distributed rate limiter needs shared state.

An in-memory implementation would behave like:

```text
Instance A → counter = 5

Instance B → counter = 5
```

The instances do not share their counters.

With Redis:

```text
Instance A ──┐
Instance B ──┼──► Redis
Instance C ──┘
```

All instances operate on the same state.

Redis also provides:

- Atomic operations
- Low-latency access
- TTL support
- Lua scripting
- Shared state across application instances

---

# Why Lua?

A naive implementation might perform:

```text
GET counter
    ↓
Check limit
    ↓
INCR counter
```

Under concurrent requests, two clients could both read the same value before either increments it.

For example:

```text
Request A → GET → 4
Request B → GET → 4

Request A → Check → Allowed
Request B → Check → Allowed

Request A → INCR
Request B → INCR
```

This can violate the intended limit.

Instead, the complete decision is executed inside Redis:

```text
GET
 ↓
Check
 ↓
Increment
 ↓
Set/refresh TTL
 ↓
Return result
```

The Lua script executes atomically within Redis.

Therefore the important property is:

> The rate-limit decision and state mutation happen atomically inside Redis.

---

# Redis TTL

Rate-limit state should not remain in Redis forever for inactive clients.

### Fixed Window

The fixed-window key expires at the end of the current window.

```text
rate-limit:fixed:<key>:<windowId>
```

### Token Bucket

Token Bucket state uses a configurable inactivity TTL.

```text
rate-limit:token:<key>
```

The TTL is a lifecycle mechanism. It is not required for the mathematical correctness of token refill.

---

# HTTP API

## Endpoint

```http
POST /api/v1/rate-limit/check
```

### Request

```json
{
  "key": "user-123"
}
```

### Allowed response

```http
HTTP/1.1 200 OK
```

```json
{
  "allowed": true,
  "remaining": 4,
  "retryAfter": 0
}
```

### Rate-limited response

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 12
```

```json
{
  "allowed": false,
  "remaining": 0,
  "retryAfter": 12
}
```

The `Retry-After` header tells the client how long it should approximately wait before retrying.

---

# Testing

The project uses multiple testing layers:

```text
Unit / Algorithm Tests
          │
          ▼
Redis-backed Tests
          │
          ▼
Concurrency Tests
          │
          ▼
Spring Integration Tests
          │
          ▼
HTTP Integration Tests
```

Current test suite:

```text
Fixed Window tests             3
Token Bucket tests            11
Controller tests               2
Application context test       1
Redis integration tests        2
HTTP integration tests         3
                               ──
Total                         22
```

Current result:

```text
Tests run: 22
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

---

# Concurrency Testing

Concurrency tests execute many requests simultaneously against the same rate-limit key.

For example:

```text
100 concurrent requests
          │
          ▼
    Redis + Lua
          │
          ▼
Only available capacity
is consumed
```

This verifies that concurrent requests cannot collectively bypass the configured limit.

---

# Deterministic Time Testing

Rate limiters depend heavily on time.

Using the system clock directly in tests can produce flaky tests.

The implementation therefore accepts a Java `Clock`.

Production:

```java
Clock.systemUTC()
```

Tests:

```java
Clock.fixed(...)
```

This allows tests to simulate time progression without actually waiting for long periods.

---

# Project Structure

```text
distributed-rate-limiter/
│
├── pom.xml
├── README.md
│
└── src/
    │
    ├── main/
    │   ├── java/
    │   │   └── com/example/distributed_rate_limiter/
    │   │       │
    │   │       ├── api/
    │   │       │   ├── RateLimitController.java
    │   │       │   ├── RateLimitRequest.java
    │   │       │   └── RateLimitResponse.java
    │   │       │
    │   │       ├── config/
    │   │       │   └── RateLimiterConfig.java
    │   │       │
    │   │       └── ratelimiter/
    │   │           ├── RateLimiter.java
    │   │           ├── RateLimitResult.java
    │   │           │
    │   │           ├── fixedwindow/
    │   │           │   └── FixedWindowRateLimiter.java
    │   │           │
    │   │           └── tokenbucket/
    │   │               └── TokenBucketRateLimiter.java
    │   │
    │   └── resources/
    │       ├── application.yml
    │       └── scripts/
    │           ├── fixed_window.lua
    │           └── token_bucket.lua
    │
    └── test/
        └── java/
            └── com/example/distributed_rate_limiter/
                │
                ├── api/
                │   └── RateLimitControllerTest.java
                │
                ├── fixedwindow/
                │   └── FixedWindowRateLimiterTest.java
                │
                ├── tokenbucket/
                │   └── TokenBucketRateLimiterTest.java
                │
                └── integration/
                    ├── RateLimiterIntegrationTest.java
                    └── RateLimitHttpIntegrationTest.java
```

---

# Running Locally

## 1. Start Redis

```bash
docker run -d \
  --name rate-limiter-redis \
  -p 6379:6379 \
  redis:latest
```

Verify:

```bash
docker exec -it rate-limiter-redis redis-cli ping
```

Expected:

```text
PONG
```

## 2. Start the application

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

The application starts on:

```text
http://localhost:8080
```

## 3. Run tests

Windows:

```powershell
.\mvnw.cmd clean test
```

---

# Design Trade-offs

## Application clock

The Token Bucket currently receives the current time from the application.

```text
Application
     │
     ▼
Clock.systemUTC()
     │
     ▼
Lua script
```

In a distributed deployment, different application instances can have small clock differences.

A future implementation could use Redis server time as a centralized time source.

## Fixed Window boundary burst

Fixed Window can allow bursts around window boundaries.

Token Bucket provides smoother rate control when this behavior is undesirable.

## Redis dependency

The limiter depends on Redis for shared state.

This provides distributed consistency but introduces Redis as an infrastructure dependency.

Redis availability and failure behavior therefore become important concerns for future versions.

## Integer Token Bucket

The current Token Bucket uses integer tokens and refill rates.

Fractional tokens could be supported in a future implementation using fixed-point arithmetic.

---

# Future Improvements

Potential future enhancements include:

- Redis server time for stronger distributed clock consistency
- Dynamic rate-limit configuration
- Multiple policies per endpoint
- Per-user and per-IP limits
- Hierarchical rate limits
- Redis failure handling
- Configurable fail-open/fail-closed behavior
- Metrics and observability
- Rate-limit response headers
- Performance benchmarking
- Load testing
- Distributed deployment

These are intentionally outside the current milestone.

---

# Engineering Focus

The core challenge of this project is not simply implementing a rate-limiting algorithm.

The main challenge is maintaining correct shared state under concurrent access across multiple application instances.

The design therefore combines:

```text
Distributed State
       +
Redis
       +
Atomic Lua Execution
       +
Concurrency Testing
       +
Deterministic Time Testing
```

The goal is to build a rate limiter that demonstrates practical distributed-systems and backend engineering concepts rather than only implementing an algorithm.
