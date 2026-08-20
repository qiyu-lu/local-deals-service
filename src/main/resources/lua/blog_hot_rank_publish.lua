-- Atomically publishes one completely staged blog hot-rank generation.
-- All KEYS must share the same Redis Cluster hash tag.
--
-- KEYS[1] live ZSET
-- KEYS[2] metadata HASH
-- KEYS[3] generation STRING
-- KEYS[4] generation-specific temporary ZSET
-- ARGV[1] builder generation
-- ARGV[2] expected candidate count
-- ARGV[3] publication time in epoch milliseconds
-- ARGV[4] configured top-K capacity

local currentGeneration = redis.call('GET', KEYS[3])
if currentGeneration ~= ARGV[1] then
    redis.call('DEL', KEYS[4])
    return 0
end

local expectedCount = tonumber(ARGV[2])
local capacity = tonumber(ARGV[4])
if not expectedCount or expectedCount < 0 or not capacity or capacity <= 0 or
        expectedCount > capacity then
    return redis.error_reply('invalid candidate count')
end

if expectedCount == 0 then
    -- An empty, ready rank is different from an uninitialized rank.
    redis.call('DEL', KEYS[1])
    redis.call('DEL', KEYS[4])
else
    if redis.call('EXISTS', KEYS[4]) == 0 then
        return -1
    end
    if redis.call('ZCARD', KEYS[4]) ~= expectedCount then
        redis.call('DEL', KEYS[4])
        return -2
    end
    redis.call('RENAME', KEYS[4], KEYS[1])
end

redis.call('HSET', KEYS[2],
        'ready', '1',
        'generation', ARGV[1],
        'count', ARGV[2],
        'capacity', ARGV[4],
        'publishedAt', ARGV[3])
return 1
