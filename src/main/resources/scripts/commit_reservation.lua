-- Commit a reservation: deletes the reservation hash and removes it from the expiry ZSET
-- but does NOT return stock to the counter. Used after payment confirmation, where
-- the decremented Redis stock now matches reality.
local reservationKey = KEYS[1]
local expiryZset = KEYS[2]

local reservationId = ARGV[1]

local existed = redis.call('EXISTS', reservationKey)
-- Drop the reservation payload after payment success; stock stays consumed.
redis.call('DEL', reservationKey)
-- Remove the expiry index entry so the sweeper does not try to release a sold reservation.
redis.call('ZREM', expiryZset, reservationId)
return existed
