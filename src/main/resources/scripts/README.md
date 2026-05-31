# Redis Lua scripts

These scripts gate the flash-sale stock counter in Redis. They're loaded once at startup via `DefaultRedisScript` beans in `RedisConfig` and invoked through `StringRedisTemplate.execute(script, keys, args)`.

## Why Lua at all?

Each script's job is a *read-then-write* against multiple keys (stock counter, reservation hash, expiry ZSET). Without Lua we'd need a `WATCH`/`MULTI`/`EXEC` optimistic-locking dance — or accept a race window between the read and the write. Redis runs Lua single-threaded, which makes the whole sequence atomic against every other Redis op, with no client-side retry loop.

All three scripts work on the same three keys, in the same order, so the script-loading and invocation code stays uniform.

| KEYS index | Key | Built by |
|---|---|---|
| `KEYS[1]` | `flashsale:stock:{productId}` (counter) | `InventoryService.stockKey` |
| `KEYS[2]` | `flashsale:reservation:{uuid}` (Hash) | `InventoryService.reservationKey` |
| `KEYS[3]` | `flashsale:reservations:expiry` (ZSET) | `InventoryService.expiryZsetKey` |

---

## `reserve_stock.lua`

**Purpose:** atomically check-and-decrement the stock counter, then record the reservation.

**ARGV:** `qty`, `reservationId`, `userId`, `productId`, `hashTtlSeconds`, `expiresAtMs`.

**Effects:**

1. If the stock key doesn't exist → return `-1` (sale not loaded).
2. If `stock < qty` → return `0` (insufficient stock).
3. Otherwise: `DECRBY stock qty`, `HSET reservation {userId,productId,qty,expiresAt}`, `EXPIRE reservation hashTtlSeconds`, `ZADD expiry expiresAtMs reservationId`, return `1`.

**Called by:** `InventoryService.reserveFlashSale` (only used in the checkout path for products with a loaded flash-sale counter).

**Return contract:** `1` = success, `0` = insufficient, `-1` = sale not loaded. The Java wrapper translates these into typed exceptions (`INSUFFICIENT_STOCK`, `FLASH_SALE_NOT_LOADED`).

---

## `release_stock.lua`

**Purpose:** return a reservation's stock to the counter and clear its bookkeeping. Idempotent — safe to call repeatedly for the same reservationId.

**ARGV:** `qty`, `reservationId`.

**Effects:**

1. If the reservation hash is **gone** → also `ZREM` the orphaned expiry entry and return `0`. (No-op path; another caller already settled this reservation.)
2. Otherwise: if the stock key still exists, `INCRBY stock qty` (skipped if admin has dropped the sale). Then `DEL reservation`, `ZREM expiry reservationId`, return `1`.

**Called by:**

- `OrderService.cancel` — explicit user cancel.
- `OrderService.checkout` — rollback in the `catch` when persistence fails after Redis already reserved.
- `ReservationSweeper.sweep` — periodic backstop for abandoned reservations (every 30 s).

**Why idempotent:** the first `EXISTS reservationKey == 0` guard. The sweeper and a user-driven cancel can race; whichever wins, the other becomes a no-op. No distributed lock needed.

---

## `commit_reservation.lua`

**Purpose:** finalize a reservation after the user pays. Deletes the bookkeeping **without** returning stock to the counter — that quantity is now permanently sold.

**ARGV:** `reservationId`.

**Effects:**

1. `EXISTS reservation` (capture as `existed` to return).
2. `DEL reservation`.
3. `ZREM expiry reservationId`.
4. Return `existed` (`1` if it was still there, `0` if already gone).

**Called by:** `OrderService.confirmPayment` — one call per flash-sale line after `paymentService.markSuccess` and the PG `available_stock` decrement.

**Why a Lua script for two `DEL`-shaped commands:** consistency with reserve/release (single round-trip, atomic, same loading mechanism). Lumping all three lifecycle operations into one place keeps the reservation state machine easy to reason about.

---

## Lifecycle summary

| Script | Stock counter | Reservation hash | Expiry ZSET | Outcome |
|---|---|---|---|---|
| `reserve_stock.lua` | `DECRBY −qty` | `HSET` (create) | `ZADD` (insert) | new reservation |
| `release_stock.lua` | `INCRBY +qty` | `DEL` | `ZREM` | stock returned |
| `commit_reservation.lua` | *unchanged* | `DEL` | `ZREM` | stock permanently sold |

Release and commit both clean up bookkeeping; the only difference is whether the stock comes back. That single difference is what encodes the payment outcome.

---

## Failure modes worth knowing

- **Redis restart loses everything.** All three scripts operate on in-memory Redis state. If Redis goes down without AOF/RDB persistence, every live reservation is gone and the counter is gone — the admin must reload the sale via `POST /api/inventory/flash-sale/load/{productId}`.
- **The reserve script does not check whether the admin already nuked the sale mid-flight.** It just checks `EXISTS stockKey`. If the admin DELs the stock key after a reservation was made, the release script's "stock key still alive?" guard skips the `INCRBY` so the counter doesn't resurrect at a wrong value — the reservation is effectively orphaned, which is the right behavior.
- **Hash retention buffer.** The reservation hash intentionally outlives the business expiry by a small buffer so the sweeper can still reconstruct `(productId, qty)` and restore stock on its next pass. If the hash is already gone when the sweeper reaches an expired ZSET entry, the buffer was exceeded and the branch is treated as an anomaly.
