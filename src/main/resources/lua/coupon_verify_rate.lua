-- Fixed one-minute window per merchant. KEYS[1] window counter; ARGV[1] TTL seconds.
-- Returns the attempt count in this window including the current one.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return count
