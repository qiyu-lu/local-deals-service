-- Atomically claims one due exact PROCESSING reservation for a reconciliation attempt.
-- The public status remains PROCESSING; moving the score provides a retry lease without
-- removing durable evidence if the worker crashes.
--
-- KEYS[1] order status Hash
-- KEYS[2] per-voucher reservation Hash
-- KEYS[3] global PROCESSING due-time ZSET
-- KEYS[4] reconciliation quarantine ZSET
-- ARGV[1] userId
-- ARGV[2] voucherId
-- ARGV[3] orderId (string)
-- ARGV[4] retry-delay seconds
--
-- Return tuple: {decision, redisNow, createdAt, reconcileAttempts}
-- 1 claimed; 2 not due; 3 terminal; 4 ownership mismatch; 5 invalid/missing state;
-- 6 reservation mismatch; 7 index member missing; 8 already quarantined.

local statusKey = KEYS[1]
local reservationKey = KEYS[2]
local processingIndexKey = KEYS[3]
local quarantineKey = KEYS[4]
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local retryDelaySeconds = tonumber(ARGV[4])

-- HINCRBY accepts only base-10 integers and its result must remain exactly representable by
-- this Lua runtime. Reconciliation attempts are operational counters, so a signed 32-bit upper
-- bound is ample and turns decimal/exponent/overflow corruption into STATE_INVALID.
local function parseAttemptCounter(raw)
    if not raw or raw == '0' then
        return 0
    end
    if not string.match(raw, '^[1-9][0-9]*$') then
        return nil
    end
    if string.len(raw) > 10 or (string.len(raw) == 10 and raw > '2147483646') then
        return nil
    end
    return tonumber(raw)
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

local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1])
if not retryDelaySeconds or retryDelaySeconds <= 0 then
    return {5, tostring(now), '', '0'}
end

if redis.call('ZSCORE', quarantineKey, orderId) then
    redis.call('ZREM', processingIndexKey, orderId)
    return {8, tostring(now), '', '0'}
end

local state = redis.call('HMGET', statusKey,
        'status', 'orderId', 'userId', 'voucherId', 'createdAt', 'reconcileAttempts')
if not state[1] then
    return {5, tostring(now), '', '0'}
end
local attempts = parseAttemptCounter(state[6])
local diagnosticAttempts = attempts or 0
if state[2] ~= orderId or state[3] ~= userId or state[4] ~= voucherId then
    return {4, tostring(now), state[5] or '', tostring(diagnosticAttempts)}
end
if state[1] == 'SUCCESS' or state[1] == 'FAILED' then
    redis.call('ZREM', processingIndexKey, orderId)
    return {3, tostring(now), state[5] or '', tostring(diagnosticAttempts)}
end
if state[1] ~= 'PROCESSING' then
    return {5, tostring(now), state[5] or '', tostring(diagnosticAttempts)}
end

local createdAt = parseCreatedAt(state[5], now)
if not createdAt or not attempts then
    return {5, tostring(now), state[5] or '', '0'}
end
if redis.call('HGET', reservationKey, userId) ~= orderId then
    return {6, tostring(now), tostring(createdAt), tostring(attempts)}
end

local dueScore = tonumber(redis.call('ZSCORE', processingIndexKey, orderId))
if not dueScore then
    return {7, tostring(now), tostring(createdAt), tostring(attempts)}
end
if dueScore > now then
    return {2, tostring(now), tostring(createdAt), tostring(attempts)}
end

attempts = redis.call('HINCRBY', statusKey, 'reconcileAttempts', 1)
redis.call('HSET', statusKey,
        'lastReconcileAt', tostring(now),
        'updatedAt', tostring(now))
redis.call('PERSIST', statusKey)
redis.call('ZADD', processingIndexKey, now + retryDelaySeconds, orderId)
return {1, tostring(now), tostring(createdAt), tostring(attempts)}
