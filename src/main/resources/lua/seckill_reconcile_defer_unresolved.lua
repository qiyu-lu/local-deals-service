-- Defers one canonical due member whose owner fields cannot be trusted enough to resolve the
-- shared user lock. This script changes only the scheduler index, never reservation/stock/status.
-- KEYS[1] order status Hash
-- KEYS[2] global PROCESSING due-time ZSET
-- KEYS[3] reconciliation quarantine ZSET
-- ARGV[1] canonical orderId string
-- ARGV[2] retry-delay seconds
-- Returns 1 deferred, 2 terminal entry removed, 3 quarantined entry removed, 0 index missing,
-- and -1 for invalid configuration.

local orderId = ARGV[1]
local retryDelaySeconds = tonumber(ARGV[2])
if not retryDelaySeconds or retryDelaySeconds <= 0 then
    return -1
end

if redis.call('ZSCORE', KEYS[3], orderId) then
    redis.call('ZREM', KEYS[2], orderId)
    return 3
end

local state = redis.call('HMGET', KEYS[1], 'status', 'orderId', 'userId', 'voucherId')
if (state[1] == 'SUCCESS' or state[1] == 'FAILED')
        and state[2] == orderId and state[3] and state[4] then
    redis.call('ZREM', KEYS[2], orderId)
    return 2
end
if not redis.call('ZSCORE', KEYS[2], orderId) then
    return 0
end

local redisTime = redis.call('TIME')
redis.call('ZADD', KEYS[2], tonumber(redisTime[1]) + retryDelaySeconds, orderId)
return 1
