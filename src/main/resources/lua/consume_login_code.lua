-- Atomically verify a code and enforce a per-code failure budget.
-- KEYS[1]: verification-code key, KEYS[2]: failure-count/lock key
-- ARGV[1]: submitted code, ARGV[2]: maximum failures
-- Returns 1 for success, 0 for a generic failure, 2 for a locked code.
local codeKey = KEYS[1]
local failureKey = KEYS[2]
local submittedCode = ARGV[1]
local maxFailures = tonumber(ARGV[2])

local failures = tonumber(redis.call('get', failureKey)) or 0
if failures >= maxFailures then
    return 2
end

local code = redis.call('get', codeKey)
if not code then
    return 0
end

if code == submittedCode then
    redis.call('del', codeKey, failureKey)
    return 1
end

-- Keep the failure counter (and the lock at the fifth failure) only until the
-- original code would have expired. Deleting the code must not clear that lock.
local remainingTtl = redis.call('pttl', codeKey)
if remainingTtl <= 0 then
    redis.call('del', codeKey, failureKey)
    return 2
end

failures = redis.call('incr', failureKey)
redis.call('pexpire', failureKey, remainingTtl)
if failures >= maxFailures then
    redis.call('set', failureKey, tostring(maxFailures), 'PX', remainingTtl)
    redis.call('del', codeKey)
    return 2
end

return 0
