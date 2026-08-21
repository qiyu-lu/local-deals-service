-- Fixed-window admission for new seckill HTTP traffic only.
-- KEYS are key prefixes sharing one voucher hash tag; the Redis-time bucket is appended here.
-- KEYS[1] activity prefix, KEYS[2] user prefix, KEYS[3] hashed-IP prefix
-- ARGV[1] window ms, ARGV[2] activity limit, ARGV[3] user limit, ARGV[4] IP limit
-- Returns 0 allowed, 1 activity, 2 user, 3 IP. No counter changes occur on rejection.

local windowMillis = tonumber(ARGV[1])
local activityLimit = tonumber(ARGV[2])
local userLimit = tonumber(ARGV[3])
local ipLimit = tonumber(ARGV[4])
if not windowMillis or windowMillis < 1000 or
        not activityLimit or activityLimit <= 0 or
        not userLimit or userLimit <= 0 or
        not ipLimit or ipLimit <= 0 then
    return redis.error_reply('invalid seckill traffic configuration')
end

local redisTime = redis.call('TIME')
local seconds = tonumber(redisTime[1])
local microseconds = tonumber(redisTime[2])
if not seconds or not microseconds then
    return redis.error_reply('invalid redis time')
end
local nowMillis = seconds * 1000 + math.floor(microseconds / 1000)
local bucket = tostring(math.floor(nowMillis / windowMillis))
local activityKey = KEYS[1] .. bucket
local userKey = KEYS[2] .. bucket
local ipKey = KEYS[3] .. bucket

local activityCount = tonumber(redis.call('GET', activityKey)) or 0
local userCount = tonumber(redis.call('GET', userKey)) or 0
local ipCount = tonumber(redis.call('GET', ipKey)) or 0
if activityCount >= activityLimit then
    return 1
end
if userCount >= userLimit then
    return 2
end
if ipCount >= ipLimit then
    return 3
end

local ttlMillis = windowMillis * 2 + 1000
redis.call('INCR', activityKey)
redis.call('INCR', userKey)
redis.call('INCR', ipKey)
redis.call('PEXPIRE', activityKey, ttlMillis)
redis.call('PEXPIRE', userKey, ttlMillis)
redis.call('PEXPIRE', ipKey, ttlMillis)
return 0
