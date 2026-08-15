--[[
Atomic seckill admission used by the RocketMQ local transaction.

KEYS[1] stock String                    seckill:stock:{voucherId}
KEYS[2] legacy purchased-user Set       seckill:order:{voucherId}
KEYS[3] activity metadata Hash          seckill:meta:{voucherId}
KEYS[4] exact reservation Hash          seckill:reservation:{voucherId}
KEYS[5] order status Hash               seckill:order:status:{orderId}

ARGV[1] userId
ARGV[2] voucherId
ARGV[3] orderId (kept as a string; never convert a 64-bit ID to a Lua number)
ARGV[4] order-status TTL in seconds

Return codes (0/1/2 retain the original public contract):
0 accepted
1 out of stock
2 duplicate purchase
3 activity has not started
4 activity ended or is not ACTIVE
5 activity metadata is absent or invalid
]]

local stockKey = KEYS[1]
local legacyOrderKey = KEYS[2]
local metaKey = KEYS[3]
local reservationKey = KEYS[4]
local orderStatusKey = KEYS[5]

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local statusTtlSeconds = tonumber(ARGV[4])

local meta = redis.call('HMGET', metaKey, 'status', 'beginAt', 'endAt')
local activityStatus = meta[1]
local beginAt = tonumber(meta[2])
local endAt = tonumber(meta[3])
if not activityStatus or not beginAt or not endAt or beginAt > endAt then
    return 5
end

if activityStatus ~= 'ACTIVE' then
    return 4
end

-- Redis server time is shared by every application instance.
local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1])
if now < beginAt then
    return 3
end
if now > endAt then
    return 4
end

local stock = tonumber(redis.call('GET', stockKey))
if not stock or stock <= 0 then
    return 1
end

-- The old Set remains authoritative for pre-migration purchases. New admissions also
-- write an exact userId -> orderId reservation so transaction checks cannot commit a
-- different half-message merely because the user bought this voucher before.
if redis.call('SISMEMBER', legacyOrderKey, userId) == 1 then
    return 2
end
if redis.call('HEXISTS', reservationKey, userId) == 1 then
    return 2
end

redis.call('DECR', stockKey)
redis.call('SADD', legacyOrderKey, userId)
redis.call('HSET', reservationKey, userId, orderId)
redis.call('HSET', orderStatusKey,
        'status', 'PROCESSING',
        'orderId', orderId,
        'userId', userId,
        'voucherId', voucherId,
        'reason', '',
        'createdAt', tostring(now),
        'updatedAt', tostring(now))
if statusTtlSeconds and statusTtlSeconds > 0 then
    redis.call('EXPIRE', orderStatusKey, statusTtlSeconds)
end

return 0
