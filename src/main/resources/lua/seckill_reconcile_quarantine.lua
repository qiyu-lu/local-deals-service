-- Atomically removes an unsafe order from automatic reconciliation while retaining a
-- timestamped, operator-visible quarantine record. The business status is not rewritten.
-- KEYS[1] global PROCESSING due-time ZSET
-- KEYS[2] quarantine ZSET
-- KEYS[3] quarantine-reason Hash
-- ARGV[1] orderId (string)
-- ARGV[2] reason
-- Returns 1 when newly quarantined and 2 when its operator evidence already existed.

local orderId = ARGV[1]
local reason = ARGV[2]
local alreadyQuarantined = redis.call('ZSCORE', KEYS[2], orderId)
local redisTime = redis.call('TIME')
redis.call('ZREM', KEYS[1], orderId)
redis.call('ZADD', KEYS[2], redisTime[1], orderId)
redis.call('HSET', KEYS[3], orderId, reason)
if alreadyQuarantined then
    return 2
end
return 1
