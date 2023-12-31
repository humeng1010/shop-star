---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by humeng.
--- DateTime: 2023/7/13 11:02
---

-- 优惠券id
local voucherId = ARGV[1]

-- 用户id
local userId = ARGV[2]

-- 订单id
local orderId = ARGV[3]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId

-- 订单key
local orderKey = 'seckill:order:' .. voucherId

-- 检查库存键是否存在
if redis.call('exists', stockKey) == 0 then
    return 3
end

-- 获取优惠券库存
local stock = redis.call('get', stockKey)
-- 判断库存是否充足
stock = tonumber(stock)
if (stock <= 0) then
    --    库存不足
    return 1
end

-- 判断用户是否下单 SISMEMBER orderKey userId
if (redis.call('sismember', orderKey, userId) == 1) then
    --    存在重复下单
    return 2
end

-- 扣减库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)

-- 下单 sadd orderKey userId
redis.call('sadd', orderKey, userId)

-- 发送消息到队列中 XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
-- 成功返回 0
return 0