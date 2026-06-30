--[[
秒杀下单检查 Lua 脚本（RocketMQ 事务消息版）

功能：
1. 判断库存是否充足
2. 判断用户是否已抢过
3. 扣减库存并记录用户
（不再发送 Stream 消息，订单消息改由 RocketMQ 事务消息承载）

参数说明：
KEYS[1] - 库存 key，例如：seckill:stock:17
KEYS[2] - 订单用户集合 key，例如：seckill:order:17

ARGV[1] - 用户 ID
ARGV[2] - 优惠券 ID
ARGV[3] - 订单 ID

返回值：
0 - 秒杀成功
1 - 库存不足
2 - 用户已抢过
]]

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

local stock = tonumber(redis.call('get', stockKey))
if not stock or stock <= 0 then
    return 1
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)
return 0
