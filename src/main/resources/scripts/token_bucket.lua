local key = KEYS[1]

local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local currentTime = tonumber(ARGV[3])
local stateTtl = tonumber(ARGV[4])

local tokens = redis.call('HGET', key, 'tokens')
local lastRefillTime = redis.call('HGET', key, 'last_refill_time')

if not tokens then
    tokens = capacity
else
    tokens = tonumber(tokens)
end

if not lastRefillTime then
    lastRefillTime = currentTime
else
    lastRefillTime = tonumber(lastRefillTime)
end

local elapsed = currentTime - lastRefillTime

if elapsed > 0 then
    tokens = math.min(
        capacity,
        tokens + (elapsed * refillRate)
    )

    lastRefillTime = currentTime
end

if tokens >= 1 then

    tokens = tokens - 1

    redis.call(
        'HSET',
        key,
        'tokens',
        tokens,
        'last_refill_time',
        lastRefillTime
    )

    redis.call(
        'EXPIRE',
        key,
        stateTtl
    )

    return {
        1,
        tokens,
        0
    }

else

    redis.call(
        'HSET',
        key,
        'tokens',
        tokens,
        'last_refill_time',
        lastRefillTime
    )

    redis.call(
        'EXPIRE',
        key,
        stateTtl
    )

    local tokensNeeded = 1 - tokens

    local retryAfter =
        math.ceil(tokensNeeded / refillRate)

    return {
        0,
        tokens,
        retryAfter
    }
end