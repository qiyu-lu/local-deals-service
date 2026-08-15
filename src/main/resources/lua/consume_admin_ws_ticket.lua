local token = redis.call('GET', KEYS[1])
if not token then
    return nil
end
redis.call('DEL', KEYS[1])
return token
