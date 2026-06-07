# flash-sale

Production-shaped Spring Boot modular monolith demonstrating a flash-sale checkout flow backed by PostgreSQL and Redis. 
The point of the project is to show how Redis is used as a correctness primitive (atomic stock reservation, idempotency, ephemeral cart) 
on top of an SQL system of record, not just as a cache.

## Stack

Java 21 · Spring Boot 3.5 · Spring Web / Data JPA / Data Redis · PostgreSQL 16 · Redis 7 · Flyway · SpringDoc OpenAPI · Testcontainers

## Modules

```
com.example.flash_sale
├── product      product catalogue + Redis read-through cache
├── inventory    PG stock + Redis flash-sale stock + Lua reserve/release/commit + expiry sweeper
├── cart         Redis-hash cart, TTL 7d
├── order        checkout / confirm / cancel, idempotency, optimistic locking
├── payment      fake-state payment aggregate (1:1 with order)
├── user         user lookup by X-User-Id
├── common       error envelope, X-User-Id resolver
└── config       Redis Lua scripts, OpenAPI, web MVC wiring
```

Module rules: order never touches inventory rows directly — it calls `InventoryService`. Product data is seeded via Flyway; there is no product CRUD API.

## Redis use cases

| Use case | Key | Why Redis |
| --- | --- | --- |
| Product detail cache | `product:{id}` (TTL 10m) | Avoid PG round trip on hot product reads. Stale-on-read is OK because product data is seeded. |
| Cart state | `cart:{userId}` Hash (TTL 7d) | Cart is ephemeral and high-write; storing it in PG would generate write amplification with no real value. |
| Flash-sale stock | `flashsale:stock:{productId}` | Counter has to support thousands of concurrent decrements without lock contention. `DECRBY` + Lua wraps the read-check-write into a single atomic step — PG row locks would queue and lose conversion. |
| Reservation hash | `flashsale:reservation:{reservationId}` Hash (TTL 10m) | Per-reservation state so we know how much to give back if the user abandons. |
| Reservation expiry index | `flashsale:reservations:expiry` ZSET | Sweeper polls expired members and restores stock; the ZSET makes "give me everything expired" O(log n). |
| Idempotency cache | `idem:checkout:{requestId}` (TTL 24h) | Stores the full checkout response so a network-retry returns the same order without a duplicate. |

If you remove Redis: oversell happens (PG row locks serialise but throughput collapses), checkout retries create duplicates, cart writes hammer PG.

## Running

```bash
docker compose up -d        # starts postgres + redis on 5432 / 6379
./gradlew bootRun
```

Swagger UI: <http://localhost:8081/swagger-ui.html>
Actuator health: <http://localhost:8081/actuator/health>

Tests (spin up their own containers):

```bash
./gradlew test
```

## Demo flow with curl

The system uses a numeric `X-User-Id` header in lieu of auth. Demo users 1–5 are seeded.

Load flash-sale stock for product 1 (admin)
```bash
curl -X POST localhost:8081/api/inventory/flash-sale/load/1
````

Inspect inventory — PG available + Redis remaining
```bash
curl localhost:8081/api/inventory/1
```


Add product 1 to user 1's cart
```bash
curl -X POST localhost:8081/api/cart/items \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' \
  -d '{"productId": 1, "quantity": 2}'
```
```bash
curl -H 'X-User-Id: 1' localhost:8081/api/cart
```

Checkout (idempotency-key required)
```bash
curl -X POST localhost:8081/api/orders/checkout \
  -H 'X-User-Id: 1' \
  -H 'Idempotency-Key: 11111111-2222-3333-4444-555555555555'
```
Retrying with the same Idempotency-Key returns the same order, no duplicate.


Confirm payment
```bash
curl -X POST localhost:8081/api/orders/1/confirm-payment -H 'X-User-Id: 1'
```

Or cancel and release stock
```bash
curl -X POST localhost:8081/api/orders/1/cancel -H 'X-User-Id: 1'
```

Browse
```bash
curl -H 'X-User-Id: 1' localhost:8081/api/orders
curl -H 'X-User-Id: 1' localhost:8081/api/orders/1
```

## Verifying Redis keys

```bash
docker compose exec redis redis-cli
> KEYS product:*
> GET product:1
> HGETALL cart:1
> GET flashsale:stock:1
> HGETALL flashsale:reservation:<uuid>
> ZRANGE flashsale:reservations:expiry 0 -1 WITHSCORES
> KEYS idem:checkout:*
```

## Reservation lifecycle

1. Checkout calls `InventoryService.reserveFlashSale` per cart line that has a loaded stock key.
2. Lua script (`reserve_stock.lua`) atomically checks remaining stock, `DECRBY`s, writes a reservation hash with a TTL, and adds the reservation id to the expiry ZSET.
3. The order is persisted with `PENDING_PAYMENT` and a payment row in `PENDING`.
4. On confirm: PG `available_stock` is decremented inside the same DB tx as the order status flip, and the reservation hash + ZSET entry are deleted (`commit_reservation.lua`) — stock is **not** returned to Redis since it's now real, committed inventory.
5. On cancel: `release_stock.lua` returns the quantity to Redis and clears the reservation. Idempotent — replaying it after a TTL expiry is a no-op.
6. The `ReservationSweeper` runs every 30s, scans the expiry ZSET for past-due entries, and runs the release script for each. Hash-already-gone is treated as "already settled, just clean the index entry."

## Implementation notes

- DTOs in controllers — entities never escape services.
- `@Version` on `orders` prevents double-confirm / confirm+cancel races.
- `BigDecimal` for prices, snapshotted onto `order_items` at checkout.
- IDs are `BIGINT GENERATED BY DEFAULT AS IDENTITY` for readable local debugging.
- `reservation_id` is per-line-item (UUID), null for non-flash-sale lines.
- Lua scripts are loaded once at startup via `DefaultRedisScript`.
- Idempotency cache stores the full JSON response so retries are byte-identical.
