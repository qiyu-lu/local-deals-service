-- Atomically enforce the per-phone cooldown and store the new login code.
-- KEYS[1]: cooldown key, KEYS[2]: verification-code key, KEYS[3]: failure-count key
-- ARGV[1]: code, ARGV[2]: code TTL seconds, ARGV[3]: cooldown seconds
if redis.call('exists', KEYS[1]) == 1 then
    return 0
end

redis.call('set', KEYS[2], ARGV[1], 'EX', ARGV[2])
redis.call('set', KEYS[1], '1', 'EX', ARGV[3])
-- A newly issued code starts with a fresh attempt budget.
redis.call('del', KEYS[3])
return 1
