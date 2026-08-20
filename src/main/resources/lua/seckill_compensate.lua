--[[
Idempotently releases one failed seckill reservation.

Only an exact userId -> orderId reservation whose order status is still PROCESSING
may restore stock. Replays after the first successful compensation are no-ops.

KEYS[1] stock String
KEYS[2] legacy purchased-user Set
KEYS[3] exact reservation Hash
KEYS[4] order status Hash
KEYS[5] global PROCESSING due-time ZSET
KEYS[6] reconciliation quarantine ZSET

ARGV[1] userId
ARGV[2] voucherId
ARGV[3] orderId
ARGV[4] failure reason
ARGV[5] order-status TTL in seconds

Returns 1 when compensation was applied, 2 for an exact already-FAILED idempotent replay,
and 0 when the reservation/state did not match.
]]

local stockKey = KEYS[1]
local legacyOrderKey = KEYS[2]
local reservationKey = KEYS[3]
local orderStatusKey = KEYS[4]
local processingIndexKey = KEYS[5]
local quarantineKey = KEYS[6]

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local reason = ARGV[4]
local statusTtlSeconds = tonumber(ARGV[5])

-- Quarantine is an operator safety boundary. No automatic caller may release stock while
-- exact ownership evidence is isolated, even if the remaining PROCESSING fields still match.
if redis.call('ZSCORE', quarantineKey, orderId) then
    return 0
end

local statusData = redis.call('HMGET', orderStatusKey, 'status', 'orderId', 'userId', 'voucherId')
-- A replay after a completed exact compensation has no reservation by design. Still heal a
-- stale due-index member and refresh the terminal retention window atomically.
if statusData[1] == 'FAILED'
        and statusData[2] == orderId
        and statusData[3] == userId
        and statusData[4] == voucherId then
    redis.call('ZREM', processingIndexKey, orderId)
    if statusTtlSeconds and statusTtlSeconds > 0 then
        redis.call('EXPIRE', orderStatusKey, statusTtlSeconds)
    end
    return 2
end

local reservedOrderId = redis.call('HGET', reservationKey, userId)
if reservedOrderId ~= orderId then
    return 0
end

if statusData[1] ~= 'PROCESSING'
        or statusData[2] ~= orderId
        or statusData[3] ~= userId
        or statusData[4] ~= voucherId then
    return 0
end

-- All guards run before the first mutation. The exact reservation is deleted in the same
-- script, so a retry cannot increment stock for this order a second time.
redis.call('INCR', stockKey)
redis.call('HDEL', reservationKey, userId)
redis.call('SREM', legacyOrderKey, userId)

local redisTime = redis.call('TIME')
local now = tostring(redisTime[1])
redis.call('HSET', orderStatusKey,
        'status', 'FAILED',
        'reason', reason,
        'updatedAt', now)
redis.call('ZREM', processingIndexKey, orderId)
if statusTtlSeconds and statusTtlSeconds > 0 then
    redis.call('EXPIRE', orderStatusKey, statusTtlSeconds)
end

return 1
