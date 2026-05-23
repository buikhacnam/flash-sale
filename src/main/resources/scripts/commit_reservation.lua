-- Commit a reservation: deletes the reservation hash and removes it from the expiry ZSET
-- but does NOT return stock to the counter. Used after payment confirmation, where
-- the decremented Redis stock now matches reality.
local reservationKey = KEYS[1]
local expiryZset = KEYS[2]

local reservationId = ARGV[1]

local existed = redis.call('EXISTS', reservationKey)
redis.call('DEL', reservationKey)
redis.call('ZREM', expiryZset, reservationId)
return existed
