-- Adds a newly committed blog to an already-ready rank without overwriting an existing score.
-- All keys share the {global} Redis Cluster hash tag.
--
-- KEYS[1] live ZSET
-- KEYS[2] metadata HASH
-- KEYS[3] generation STRING
-- ARGV[1] zero-padded 19-digit blog id
-- ARGV[2] configured top-K bound

if redis.call('HGET', KEYS[2], 'ready') ~= '1' then
    return 0
end

local topK = tonumber(ARGV[2])
if not topK or topK <= 0 then
    return redis.error_reply('invalid top-k')
end

-- Validate every value which could make a later command fail before mutating the live ZSET.
local generation = redis.call('GET', KEYS[3])
local metaGeneration = redis.call('HGET', KEYS[2], 'generation')
local metaCapacity = redis.call('HGET', KEYS[2], 'capacity')
local function validGeneration(value)
    return value and string.match(value, '^[1-9][0-9]*$') and
            string.len(value) <= 19 and
            (string.len(value) < 19 or value < '9223372036854775807')
end
local function lessThan(left, right)
    return string.len(left) < string.len(right) or
            (string.len(left) == string.len(right) and left < right)
end
-- generation may be one step ahead while a builder is loading MySQL. That is precisely the
-- window this script must fence, so only a generation rollback is invalid.
if not validGeneration(generation) or not validGeneration(metaGeneration) or
        lessThan(generation, metaGeneration) or metaCapacity ~= ARGV[2] then
    return redis.error_reply('invalid generation metadata')
end

local added = redis.call('ZADD', KEYS[1], 'NX', 0, ARGV[1])
if added == 0 then
    return 0
end
local size = redis.call('ZCARD', KEYS[1])
if size > topK then
    -- Ascending rank is score ASC, then member ASC. Removing the lowest ranks is therefore
    -- exactly the inverse of the ZREVRANGE read order, including zero-score id tie-breaking.
    redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - topK - 1)
end
-- If the zero-score newcomer was itself outside top-K, the live rank is unchanged and there is
-- no reason to invalidate an in-flight DB builder.
if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then
    return 0
end
-- Fence a builder which loaded MySQL before this committed blog existed. Its publication
-- script now observes a stale generation and cannot rename the old snapshot over this update.
local nextGeneration = redis.call('INCR', KEYS[3])
redis.call('HSET', KEYS[2],
        'generation', tostring(nextGeneration),
        'count', tostring(redis.call('ZCARD', KEYS[1])))
return added
