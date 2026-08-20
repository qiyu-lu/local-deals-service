-- Read-only, server-time-consistent PROCESSING backlog sample.
-- KEYS[1] due-time ZSET; KEYS[2] quarantine ZSET.

local nowParts = redis.call('TIME')
local now = tonumber(nowParts[1])
if not now then
    return redis.error_reply('invalid redis time')
end

local due = redis.call('ZCOUNT', KEYS[1], '-inf', now)
local oldestOverdue = 0
if due > 0 then
    local oldest = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now,
            'WITHSCORES', 'LIMIT', 0, 1)
    if #oldest ~= 2 then
        return redis.error_reply('invalid oldest due member')
    end
    local oldestScore = tonumber(oldest[2])
    if not oldestScore then
        return redis.error_reply('invalid oldest due score')
    end
    oldestOverdue = math.max(0, now - oldestScore)
end

local quarantine = redis.call('ZCARD', KEYS[2])
return tostring(due) .. '|' .. tostring(oldestOverdue) .. '|' .. tostring(quarantine)
