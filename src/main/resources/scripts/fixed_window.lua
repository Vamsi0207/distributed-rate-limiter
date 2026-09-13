local current = redis.call('GET', KEYS[1])

if not current then
    current = 0
else
    current = tonumber(current)
end

local limit = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

if current >= limit then
    local remainingTtl = redis.call('TTL', KEYS[1])

    return {0, 0, remainingTtl}
end

local newCount = redis.call('INCR', KEYS[1])

if newCount == 1 then
    redis.call('EXPIRE', KEYS[1], ttl)
end

local remaining = limit - newCount

return {1, remaining, 0}