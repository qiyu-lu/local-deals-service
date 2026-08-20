-- Idempotently finalize one exact Redis reservation after its DB order commits.
-- KEYS[1] order-status Hash, KEYS[2] per-voucher reservation Hash,
-- KEYS[3] global PROCESSING due-time ZSET
-- ARGV[1] userId, ARGV[2] voucherId, ARGV[3] orderId, ARGV[4] status TTL seconds

local statusKey = KEYS[1]
local reservationKey = KEYS[2]
local processingIndexKey = KEYS[3]
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local statusTtlSeconds = tonumber(ARGV[4])

if redis.call('HGET', reservationKey, userId) ~= orderId then
    return 0
end

local state = redis.call('HMGET', statusKey, 'status', 'orderId', 'userId', 'voucherId')
if state[2] ~= orderId or state[3] ~= userId or state[4] ~= voucherId then
    return 0
end

if state[1] == 'SUCCESS' then
    redis.call('ZREM', processingIndexKey, orderId)
    if statusTtlSeconds and statusTtlSeconds > 0 then
        redis.call('EXPIRE', statusKey, statusTtlSeconds)
    end
    return 1
end
if state[1] ~= 'PROCESSING' then
    return 0
end

local redisTime = redis.call('TIME')
redis.call('HSET', statusKey,
        'status', 'SUCCESS',
        'updatedAt', tostring(redisTime[1]))
redis.call('HDEL', statusKey, 'reason')
redis.call('ZREM', processingIndexKey, orderId)
if statusTtlSeconds and statusTtlSeconds > 0 then
    redis.call('EXPIRE', statusKey, statusTtlSeconds)
end
return 1
