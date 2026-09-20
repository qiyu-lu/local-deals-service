--[[
Classifies and claims a whole consumer batch in one round trip.

It answers the same questions as seckill_validate_reservation.lua and additionally leases
each claimable reservation to the caller: while the lease holds, the reconciler sees the
member as not due (the due score is pushed forward) and refuses it explicitly (claimOwner).
This is what replaces the per-message Redisson lock the consumer used before M4.

KEYS[1]            global PROCESSING due-time ZSET
KEYS[2i], KEYS[2i+1] order status Hash and per-voucher reservation Hash of message i

ARGV[1]            claim owner token
ARGV[2]            lease seconds
ARGV[3i], [3i+1], [3i+2]  userId, voucherId, orderId of message i

Per message result:
0 status absent/incomplete/unknown (retry; it may be temporarily unavailable)
1 claimed (safe to persist)
2 exact SUCCESS (idempotent ACK)
3 exact FAILED (compensated terminal ACK)
4 ownership mismatch, or PROCESSING without the exact reservation (poison)
5 another worker holds an unexpired claim (retry later)
]]

local processingIndexKey = KEYS[1]
local owner = ARGV[1]
local leaseSeconds = tonumber(ARGV[2])
if not owner or owner == '' or not leaseSeconds or leaseSeconds <= 0 then
    return redis.error_reply('seckill batch claim requires an owner and a positive lease')
end

local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1])

local results = {}
local count = (#ARGV - 2) / 3

for i = 1, count do
    local statusKey = KEYS[2 * i]
    local reservationKey = KEYS[2 * i + 1]
    local userId = ARGV[3 * i]
    local voucherId = ARGV[3 * i + 1]
    local orderId = ARGV[3 * i + 2]

    local decision
    local state = redis.call('HMGET', statusKey,
            'status', 'orderId', 'userId', 'voucherId', 'claimOwner', 'claimExpireAt')
    if not state[1] or not state[2] or not state[3] or not state[4] then
        decision = 0
    elseif state[2] ~= orderId or state[3] ~= userId or state[4] ~= voucherId then
        decision = 4
    elseif state[1] == 'SUCCESS' then
        decision = 2
    elseif state[1] == 'FAILED' then
        decision = 3
    elseif state[1] ~= 'PROCESSING' then
        decision = 0
    elseif redis.call('HGET', reservationKey, userId) ~= orderId then
        decision = 4
    else
        local claimOwner = state[5]
        local claimExpireAt = tonumber(state[6] or '0') or 0
        if claimOwner and claimOwner ~= '' and claimOwner ~= owner and claimExpireAt > now then
            decision = 5
        else
            redis.call('HSET', statusKey,
                    'claimOwner', owner,
                    'claimExpireAt', tostring(now + leaseSeconds))
            -- 'XX' keeps a finalized order from being re-queued by a late claim; 'GT' keeps a
            -- lease shorter than the stale-after window from pulling the due time closer and
            -- handing the reconciler orders it could only refuse.
            redis.call('ZADD', processingIndexKey, 'XX', 'GT', now + leaseSeconds, orderId)
            decision = 1
        end
    end
    results[i] = decision
end

return results
