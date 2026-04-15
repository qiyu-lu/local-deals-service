--[[
秒杀下单 Lua 脚本（升级版：集成消息队列）

功能：
1. 判断库存是否充足
2. 判断用户是否已抢过
3. 扣减库存并记录用户
4. 发送订单消息到 Stream 消息队列

参数说明：
KEYS[1] - 库存 key，例如：seckill:stock:17
KEYS[2] - 订单用户集合 key，例如：seckill:order:17
KEYS[3] - Stream 消息队列 key，例如：stream.orders

ARGV[1] - 用户 ID
ARGV[2] - 优惠券 ID
ARGV[3] - 订单 ID

返回值：
0 - 秒杀成功
1 - 库存不足
2 - 用户已抢过
]]

-- 1. 获取参数
local stockKey = KEYS[1]
local orderKey = KEYS[2]
local streamKey = KEYS[3]
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

-- 2. 查询库存（处理 key 不存在的情况）
local stock = tonumber(redis.call('get', stockKey))
if not stock or stock <= 0 then
    return 1
end

-- 3. 判断是否已下单
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 4. 扣减库存
redis.call('decr', stockKey)

-- 5. 记录用户
redis.call('sadd', orderKey, userId)

-- 6. 发送消息到 Stream 消息队列
redis.call('xadd', streamKey, '*', 'userId', userId, 'voucherId', voucherId, 'orderId', orderId)

-- 7. 返回成功
return 0
