--[[
The whole seckill admission in one round trip: rate limits, activity window, duplicate buyer,
stock and the exact PROCESSING reservation. Nothing is written unless every check passes,
except the rate-limit counters of a request that passed its limits.

Every key belongs to the buyer's stock bucket b = userId % K and carries its hash tag
{sk:b<b>}, so the whole call lives in one Cluster slot. The stock this script reads and
decrements is the bucket's share, not the voucher's total.

KEYS[1] stock String                    sk:{sk:b<b>}:stock:<voucherId>
KEYS[2] activity metadata Hash          sk:{sk:b<b>}:meta:<voucherId>     (replicated per bucket)
KEYS[3] exact reservation Hash          sk:{sk:b<b>}:resv:<voucherId>
KEYS[4] order status Hash               sk:{sk:b<b>}:status:<orderId>
KEYS[5] bucket PROCESSING due-time ZSET  sk:{sk:b<b>}:processing
KEYS[6] user rate-window prefix         sk:{sk:b<b>}:traffic:<voucherId>:user:<userId>:
KEYS[7] IP rate-window prefix           sk:{sk:b<b>}:traffic:<voucherId>:ip:<sha256(ip)>:

ARGV[1] userId
ARGV[2] voucherId
ARGV[3] orderId (kept as a string; never convert a 64-bit ID to a Lua number)
ARGV[4] stale-after interval in seconds
ARGV[5] rate window in milliseconds
ARGV[6] user limit per window (0 = off)
ARGV[7] IP limit per window (0 = off)

Returns {code, remainingStock}; remainingStock is -1 when the stock was not read.
0 accepted
1 out of stock
2 duplicate purchase
3 activity has not started
4 activity ended or is not ACTIVE
5 activity metadata is absent or invalid
6 user rate limited
7 IP rate limited
8 order id already owns a status (the caller takes a new id)
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
local windowMillis = tonumber(ARGV[5])
local userLimit = tonumber(ARGV[6])
local ipLimit = tonumber(ARGV[7])

if not staleAfterSeconds or staleAfterSeconds <= 0
        or not windowMillis or windowMillis < 1000
        or not userLimit or userLimit < 0
        or not ipLimit or ipLimit < 0 then
    return {5, -1}
end

-- Redis server time is shared by every application instance.
local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1])

if userLimit > 0 or ipLimit > 0 then
    local bucket = tostring(math.floor((now * 1000 + math.floor(tonumber(redisTime[2]) / 1000)) / windowMillis))
    local userRateKey = KEYS[6] .. bucket
    local ipRateKey = KEYS[7] .. bucket
    if userLimit > 0 and (tonumber(redis.call('GET', userRateKey)) or 0) >= userLimit then
        return {6, -1}
    end
    if ipLimit > 0 and (tonumber(redis.call('GET', ipRateKey)) or 0) >= ipLimit then
        return {7, -1}
    end
    local ttlMillis = windowMillis * 2 + 1000
    if userLimit > 0 then
        redis.call('INCR', userRateKey)
        redis.call('PEXPIRE', userRateKey, ttlMillis)
    end
    if ipLimit > 0 then
        redis.call('INCR', ipRateKey)
        redis.call('PEXPIRE', ipRateKey, ttlMillis)
    end
end

local meta = redis.call('HMGET', metaKey, 'status', 'beginAt', 'endAt')
local activityStatus = meta[1]
local beginAt = tonumber(meta[2])
local endAt = tonumber(meta[3])
if not activityStatus or not beginAt or not endAt or beginAt > endAt then
    return {5, -1}
end
if activityStatus ~= 'ACTIVE' then
    return {4, -1}
end
if now < beginAt then
    return {3, -1}
end
if now > endAt then
    return {4, -1}
end

local stock = tonumber(redis.call('GET', stockKey))
if not stock or stock <= 0 then
    return {1, 0}
end

-- The exact userId -> orderId reservation is both the duplicate-purchase guard and the
-- ownership evidence used by the consumer, compensation and reconciliation scripts.
if redis.call('HEXISTS', reservationKey, userId) == 1 then
    return {2, stock}
end

-- A status Hash belongs to exactly one order. Two instances holding the same Snowflake
-- worker id (a lost lease) could issue the same id; the second one is refused here.
if redis.call('EXISTS', orderStatusKey) == 1 then
    return {8, stock}
end

local remaining = redis.call('DECR', stockKey)
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

return {0, remaining}
