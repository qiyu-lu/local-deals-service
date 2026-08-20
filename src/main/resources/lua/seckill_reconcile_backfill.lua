-- Adds one old exact PROCESSING reservation to the due index without overwriting a live score.
-- Exact old PROCESSING status hashes are persisted because their reservation and stock do not
-- expire. Terminal, malformed, or cross-owned state is never indexed.
--
-- KEYS[1] order status Hash
-- KEYS[2] per-voucher reservation Hash
-- KEYS[3] global PROCESSING due-time ZSET
-- KEYS[4] reconciliation quarantine ZSET
-- ARGV[1] userId
-- ARGV[2] voucherId
-- ARGV[3] orderId (string)
-- ARGV[4] stale-after seconds
--
-- 1 indexed; 2 already indexed; 3 terminal; 4 ownership mismatch;
-- 5 invalid/missing state; 6 reservation mismatch; 7 already quarantined.

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local staleAfterSeconds = tonumber(ARGV[4])
if not staleAfterSeconds or staleAfterSeconds <= 0 then
    return 5
end

local function parseCreatedAt(raw, now)
    if not raw or not string.match(raw, '^[1-9][0-9]*$') or string.len(raw) > 10 then
        return nil
    end
    local parsed = tonumber(raw)
    if not parsed or parsed <= 0 or parsed > now then
        return nil
    end
    return parsed
end

if redis.call('ZSCORE', KEYS[4], orderId) then
    redis.call('ZREM', KEYS[3], orderId)
    return 7
end

local state = redis.call('HMGET', KEYS[1], 'status', 'orderId', 'userId', 'voucherId', 'createdAt')
if not state[1] then
    return 5
end
if state[2] ~= orderId or state[3] ~= userId or state[4] ~= voucherId then
    return 4
end
if state[1] == 'SUCCESS' or state[1] == 'FAILED' then
    redis.call('ZREM', KEYS[3], orderId)
    return 3
end
if state[1] ~= 'PROCESSING' then
    return 5
end

local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1])
local createdAt = parseCreatedAt(state[5], now)
if not createdAt then
    return 5
end
if redis.call('HGET', KEYS[2], userId) ~= orderId then
    return 6
end

redis.call('PERSIST', KEYS[1])
local added = redis.call('ZADD', KEYS[3], 'NX', createdAt + staleAfterSeconds, orderId)
if added == 1 then
    return 1
end
return 2
