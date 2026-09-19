--[[
Atomic seckill admission used by the RocketMQ local transaction.

KEYS[1] stock String                    seckill:stock:{voucherId}
KEYS[2] activity metadata Hash          seckill:meta:{voucherId}
KEYS[3] exact reservation Hash          seckill:reservation:{voucherId}
KEYS[4] order status Hash               seckill:order:status:{orderId}
KEYS[5] global PROCESSING due-time ZSET  seckill:order:processing

ARGV[1] userId
ARGV[2] voucherId
ARGV[3] orderId (kept as a string; never convert a 64-bit ID to a Lua number)
ARGV[4] stale-after interval in seconds

Return codes (0/1/2 retain the original public contract):
0 accepted
1 out of stock
2 duplicate purchase
3 activity has not started
4 activity ended or is not ACTIVE
5 activity metadata is absent or invalid
]]

local stockKey = KEYS[1]
local metaKey = KEYS[2]
local reservationKey = KEYS[3]
local orderStatusKey = KEYS[4]
local processingIndexKey = KEYS[5]

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local staleAfterSeconds = tonumber(ARGV[4])

if not staleAfterSeconds or staleAfterSeconds <= 0 then
    return 5
end

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

-- The exact userId -> orderId reservation is both the duplicate-purchase guard and the
-- ownership evidence used by the consumer, compensation and reconciliation scripts.
if redis.call('HEXISTS', reservationKey, userId) == 1 then
    return 2
end

redis.call('DECR', stockKey)
redis.call('HSET', reservationKey, userId, orderId)
redis.call('HSET', orderStatusKey,
        'status', 'PROCESSING',
        'orderId', orderId,
        'userId', userId,
        'voucherId', voucherId,
        'reason', '',
        'createdAt', tostring(now),
        'updatedAt', tostring(now),
        'reconcileAttempts', '0')
-- A PROCESSING state must not disappear while its stock/reservation remain durable. Terminal
-- transitions apply the bounded seven-day TTL after removing this order from the due index.
redis.call('PERSIST', orderStatusKey)
redis.call('ZADD', processingIndexKey, now + staleAfterSeconds, orderId)

return 0
