-- Atomic flash-sale stock reservation.
-- Decrements stock only if enough is available, then creates the reservation hash
-- and registers it in the expiry ZSET. Single-shot, no partial state on failure.
local stockKey = KEYS[1]
local reservationKey = KEYS[2]
local expiryZset = KEYS[3]

local qty = tonumber(ARGV[1])
local reservationId = ARGV[2]
local userId = ARGV[3]
local productId = ARGV[4]
local hashTtlSeconds = tonumber(ARGV[5])
local expiresAtMs = tonumber(ARGV[6])

if redis.call('EXISTS', stockKey) == 0 then
    return -1
end

local current = tonumber(redis.call('GET', stockKey))
if current == nil or current < qty then
    return 0
end

-- Consume the reserved quantity from the flash-sale counter so later checkouts see less stock.
redis.call('DECRBY', stockKey, qty)
-- Persist reservation metadata needed for confirm/cancel/sweeper paths.
redis.call('HSET', reservationKey,
    'userId', userId,
    'productId', productId,
    'qty', qty,
    'expiresAt', expiresAtMs)
-- Keep the reservation hash alive long enough for expiry recovery and rollback handling.
redis.call('EXPIRE', reservationKey, hashTtlSeconds)
-- Index the reservation by expiry time so the sweeper can find timed-out reservations efficiently.
redis.call('ZADD', expiryZset, expiresAtMs, reservationId)
return 1
