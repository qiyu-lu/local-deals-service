-- Returns, without removing them, at most ARGV[1] PROCESSING order ids whose due score
-- is no later than Redis server time. Order ids remain strings throughout.
-- KEYS[1] global PROCESSING due-time ZSET
-- ARGV[1] bounded batch size

local limit = tonumber(ARGV[1])
if not limit or limit <= 0 then
    return {}
end

local redisTime = redis.call('TIME')
return redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', redisTime[1], 'LIMIT', 0, limit)
