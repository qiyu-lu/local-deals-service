--[[
Finalizes a whole batch of committed orders in one round trip.

Per message this is exactly seckill_mark_success.lua, plus the release of the claim the
batch took in seckill_batch_claim.lua.

KEYS[1]            global PROCESSING due-time ZSET
KEYS[2i], KEYS[2i+1] order status Hash and per-voucher reservation Hash of message i

ARGV[1]            order-status TTL seconds
ARGV[3i-1], [3i], [3i+1]  userId, voucherId, orderId of message i

Per message result: 1 when the exact reservation is SUCCESS afterwards, 0 otherwise.
]]

local processingIndexKey = KEYS[1]
local statusTtlSeconds = tonumber(ARGV[1])

local results = {}
local count = (#ARGV - 1) / 3
local redisTime = redis.call('TIME')
local now = tostring(redisTime[1])

for i = 1, count do
    local statusKey = KEYS[2 * i]
    local reservationKey = KEYS[2 * i + 1]
    local userId = ARGV[3 * i - 1]
    local voucherId = ARGV[3 * i]
    local orderId = ARGV[3 * i + 1]

    local finalized = 0
    if redis.call('HGET', reservationKey, userId) == orderId then
        local state = redis.call('HMGET', statusKey, 'status', 'orderId', 'userId', 'voucherId')
        if state[2] == orderId and state[3] == userId and state[4] == voucherId then
            if state[1] == 'SUCCESS' then
                redis.call('ZREM', processingIndexKey, orderId)
                redis.call('HDEL', statusKey, 'claimOwner', 'claimExpireAt')
                if statusTtlSeconds and statusTtlSeconds > 0 then
                    redis.call('EXPIRE', statusKey, statusTtlSeconds)
                end
                finalized = 1
            elseif state[1] == 'PROCESSING' then
                redis.call('HSET', statusKey, 'status', 'SUCCESS', 'updatedAt', now)
                redis.call('HDEL', statusKey, 'reason', 'claimOwner', 'claimExpireAt')
                redis.call('ZREM', processingIndexKey, orderId)
                if statusTtlSeconds and statusTtlSeconds > 0 then
                    redis.call('EXPIRE', statusKey, statusTtlSeconds)
                end
                finalized = 1
            end
        end
    end
    results[i] = finalized
end

return results
