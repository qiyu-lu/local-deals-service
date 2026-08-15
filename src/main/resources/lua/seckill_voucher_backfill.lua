-- Backfill one voucher without clobbering live Redis state.
-- KEYS[1]: stock String
-- KEYS[2]: activity metadata Hash
-- ARGV[1]: database stock
-- ARGV[2]: begin epoch second
-- ARGV[3]: end epoch second
-- Returns a bit mask: stock=1, status=2, beginAt=4, endAt=8.

local changed = 0

if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SET', KEYS[1], ARGV[1])
    changed = changed + 1
end

if redis.call('HEXISTS', KEYS[2], 'status') == 0 then
    redis.call('HSET', KEYS[2], 'status', 'ACTIVE')
    changed = changed + 2
end

if redis.call('HEXISTS', KEYS[2], 'beginAt') == 0 then
    redis.call('HSET', KEYS[2], 'beginAt', ARGV[2])
    changed = changed + 4
end

if redis.call('HEXISTS', KEYS[2], 'endAt') == 0 then
    redis.call('HSET', KEYS[2], 'endAt', ARGV[3])
    changed = changed + 8
end

return changed
