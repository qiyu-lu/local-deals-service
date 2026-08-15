--[[
Validates that one RocketMQ message still owns the exact Redis reservation before
the consumer is allowed to mutate MySQL. This script is deliberately read-only.

KEYS[1] order status Hash
KEYS[2] per-voucher reservation Hash

ARGV[1] userId
ARGV[2] voucherId
ARGV[3] orderId

Return codes:
0 status is absent, incomplete, or unknown (retry; it may be temporarily unavailable)
1 exact PROCESSING status and exact reservation (safe to persist)
2 exact SUCCESS status (idempotent ACK)
3 exact FAILED status (compensated terminal ACK)
4 ownership mismatch or PROCESSING without the exact reservation (poison ACK)
]]

local statusKey = KEYS[1]
local reservationKey = KEYS[2]
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

local state = redis.call('HMGET', statusKey, 'status', 'orderId', 'userId', 'voucherId')
if not state[1] or not state[2] or not state[3] or not state[4] then
    return 0
end

if state[2] ~= orderId or state[3] ~= userId or state[4] ~= voucherId then
    return 4
end

if state[1] == 'SUCCESS' then
    return 2
end
if state[1] == 'FAILED' then
    return 3
end
if state[1] ~= 'PROCESSING' then
    return 0
end

if redis.call('HGET', reservationKey, userId) ~= orderId then
    return 4
end

return 1
