-- Idempotent release. Used for both explicit cancel and sweeper-driven expiry.
-- If the reservation hash is gone we assume it was already released or committed
-- and do nothing — that's what keeps the sweeper safe to run repeatedly.
local stockKey = KEYS[1]
local reservationKey = KEYS[2]
local expiryZset = KEYS[3]

local qty = tonumber(ARGV[1])
local reservationId = ARGV[2]

if redis.call('EXISTS', reservationKey) == 0 then
    redis.call('ZREM', expiryZset, reservationId)
    return 0
end

if redis.call('EXISTS', stockKey) == 1 then
    redis.call('INCRBY', stockKey, qty)
end
redis.call('DEL', reservationKey)
redis.call('ZREM', expiryZset, reservationId)
return 1
