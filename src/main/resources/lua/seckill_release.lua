--[[
Gives one closed or refunded order's unit back to the admission layer and lets the user buy
again. Idempotent without a marker: the user's reservation points at this order only until it
is released, and a later purchase writes a different order id, so a replay finds nothing to do.

KEYS[1] stock String          seckill:stock:{voucherId}
KEYS[2] reservation Hash      seckill:reservation:{voucherId}  (userId -> orderId)
ARGV[1] userId
ARGV[2] orderNo

Returns 1 when released now, 0 when nothing was held for this order.
]]

local stockKey = KEYS[1]
local reservationKey = KEYS[2]
local userId = ARGV[1]
local orderNo = ARGV[2]

if redis.call('HGET', reservationKey, userId) ~= orderNo then
    return 0
end

redis.call('HDEL', reservationKey, userId)
-- A missing stock key means the activity was never preheated or was removed; creating it
-- here would admit buyers against stock nobody configured.
if redis.call('EXISTS', stockKey) == 1 then
    redis.call('INCR', stockKey)
end
return 1
