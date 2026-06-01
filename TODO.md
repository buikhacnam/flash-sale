# TODO

## 1. Normal-order abandonment — no automatic stock release

### Problem

When a user checks out a non-flash-sale item, `available_stock` is decremented in PG immediately (under `SELECT FOR UPDATE`). The order sits in `PENDING_PAYMENT`. If the user never confirms and never explicitly cancels, the stock stays reserved **indefinitely** — there is no TTL, no sweeper, no automatic recovery.

For flash-sale items the problem is solved by the Redis hash TTL + `ReservationSweeper`. Normal items have no equivalent.

### Possible solutions

1. **Redis TTL key shadowing each pending order + keyspace event listener** *(preferred)*
   - On checkout: `SET order:expiry:{orderId} {orderId} EX {ttl}`
   - On confirm / cancel: `DEL order:expiry:{orderId}`
   - Enable Redis keyspace notifications: `CONFIG SET notify-keyspace-events Ex` (server-side; also needs `command: redis-server --notify-keyspace-events Ex` in `compose.yaml` and in `TestcontainersConfiguration`).
   - Spring `KeyExpirationEventMessageListener` subscribes to `__keyevent@0__:expired`; on a key matching `order:expiry:*`, call `OrderService.expireOrder(orderId)`.
   - Pros: event-driven, near-instant expiry. Symmetric with the flash-sale Redis-TTL pattern.
   - Cons: notifications are **best-effort** — not persisted, not replayed. If Redis restarts or the listener is down when the event fires, the expiry is lost. A periodic safety-net reconciler would close this gap.

2. **PG-side scheduled sweeper**
   - Add `expires_at TIMESTAMPTZ` to `orders`, set at checkout.
   - `@Scheduled` job runs every N seconds, finds `WHERE status='PENDING_PAYMENT' AND expires_at < now()`, calls `OrderService.expireOrder` for each.
   - Pros: durable, simple, no Redis config gymnastics, survives Redis restarts.
   - Cons: polling (up to N seconds of lag), adds DB load, asymmetric with the flash-sale flow.

3. **Lazy expiry at read/access time**
   - Check `expires_at` whenever the order is loaded (e.g. in `requireOwnedOrder`, `confirmPayment`).
   - If past, expire it inline before continuing.
   - Pros: zero background machinery.
   - Cons: stock is held until someone *touches* the order again — could be never. Not a real solution; only good as a complement to #1 or #2.

### Decision

Defer. Option 1 is the right shape (matches the rest of the architecture) but the lost-event caveat means a real implementation also needs a low-frequency PG safety-net reconciler — essentially #1 + a watered-down #2. Not blocking for the demo.

---

## 2. Flash sale has no user-visible benefit

### Problem

The flash-sale path currently differs from the normal path only in *how* stock is reserved (Redis Lua vs PG `FOR UPDATE`). Price, time window, and per-user limits are identical. `order_items.unit_price` is snapshotted from `products.price` regardless of path, so a buyer sees no discount, no exclusivity, no "you got the deal" signal. The demo models the *backpressure mechanics* of a flash sale but not the *value proposition*.

### Possible solutions

1. **Discount price column on `inventory`**
   - Add `flash_sale_price NUMERIC(12,2)`.
   - At checkout, for lines that have a loaded Redis stock key, snapshot `flash_sale_price` onto `order_items.unit_price` instead of `products.price`.
   - Cheapest change with the biggest UX impact.

2. **Time window**
   - Add `flash_sale_starts_at` / `flash_sale_ends_at TIMESTAMPTZ` on `inventory`.
   - `POST /api/inventory/flash-sale/load/{productId}` validates that `now BETWEEN start AND end`; checkout rejects reservations outside the window.
   - Could also be modelled as a separate `flash_sales` table if multiple sales per product are needed.

3. **Per-user purchase limit**
   - Redis hash `flashsale:purchases:{productId}` keyed by `userId`, value = qty bought.
   - Atomic check + increment in the reserve Lua script — refuses reservation if user would exceed the cap.
   - Cleared at sale end (or TTL'd to the sale end timestamp).

4. **Marketing surface on listing**
   - `GET /api/products` returns a `flashSale: { active, price, endsAt, remainingStock }` block per product when applicable.
   - Pulls `remainingStock` from Redis (`flashsale:stock:{pid}`), price/window from PG.
   - Lets a frontend render "FLASH SALE — 30% OFF, 4 LEFT" without a second round-trip.

### Decision

Defer. #1 + #4 together would be the smallest meaningful slice (real discount + visibility). #2 and #3 are real-system necessities but not needed to demonstrate the value prop. Not blocking for the demo.

---

## 3. Flash-sale checkout still writes synchronously to PG

### Problem

Even though Redis gates the stock counter, every *successful* flash-sale checkout still does a synchronous PG transaction at request time (`INSERT orders` + `INSERT order_items` + `INSERT payments`). Under a true spike (e.g. 50k users, 10k units, sold out in 2 seconds), that's 10k PG transactions hitting `orders` / `payments` in those same seconds. PG can absorb it, but:

- Connection-pool pressure: each checkout holds a JDBC connection for the duration of the tx + the Redis network round-trip.
- Tail latency: a slow PG fsync / autovacuum / replication lag spike now slows down user-facing checkout.
- The Redis success → PG write step is **not atomic**: if the app crashes between the Lua reserve and the `INSERT`, the stock is decremented in Redis with no order row to back it. The sweeper will eventually return it, but the user sees a failed checkout despite having "won" the reservation.

### Possible solutions

1. **Kafka / RabbitMQ / SQS between checkout and PG persistence**
   - Checkout flow: Redis reserve → publish `OrderCreated` event with `{userId, items, reservationIds, idempotencyKey}` → return `202 Accepted` with a synthetic order id (or "processing" status).
   - Background consumer drains the topic, does the PG INSERTs, updates order status to `PENDING_PAYMENT`.
   - Frontend polls `GET /api/orders/{id}` or subscribes to a websocket for the final state.
   - Pros: PG is decoupled from the user-facing critical path. The Redis-reserved-but-not-persisted gap becomes a queue length, not lost orders.
   - Cons: response no longer carries the persisted order; clients need to handle "pending materialization." More moving parts (broker, consumer, DLQ, replay tooling).

2. **Outbox pattern via Trigger.dev / Cron job batching inserts**
   - Same idea but with a simpler delivery substrate — periodic flush instead of a streaming broker.
   - Pros: cheaper to operate.
   - Cons: latency floor = flush interval; throughput ceiling = batch size.

3. **Postgres async commit / unlogged staging table**
   - Write to an `orders_staging` UNLOGGED table at checkout, copy to `orders` in a background job.
   - Pros: no broker, all SQL.
   - Cons: UNLOGGED tables don't survive crashes (defeats durability). Async commit narrows the window but doesn't close it.

4. **Status: `RESERVED` → `PENDING_PAYMENT` two-phase state**
   - Even without a queue, write a tiny `INSERT orders (status='RESERVED')` immediately and finish the heavier write (items, payment) in a follow-up tx.
   - Pros: minimal infra change.
   - Cons: still synchronous, just split. Doesn't fix the connection-pool pressure problem.

### Decision

Defer. Option 1 (Kafka or equivalent) is the right answer for a real flash-sale system but is a meaningful architectural shift — it changes the response contract and adds operational surface area (broker, DLQ, consumer scaling). Worth doing as a follow-up demo *after* the discount/window/limit work, because that's where queue-vs-sync trade-offs become tangible. Reasonable platform choices in this org: Trigger.dev (managed), self-hosted Kafka on Railway/GCP, or SQS/SNS via AWS.

---

## confirmPayment can leave orphaned state if a step after the Redis commit fails

### Problem

`OrderService.confirmPayment` is `@Transactional`, but the Redis bookkeeping calls (`commitReservation`, `cartService.clear`) sit *inside* the PG tx. The sequence is:

1. `paymentService.markSuccess` (PG)
2. `decrementPgStockForFlashSaleLines` (PG)
3. `commitReservation` per flash-sale line (Redis — DEL hash + ZREM expiry; stock counter stays decremented)
4. `order.markConfirmed` (PG)
5. `cartService.clear` (Redis)
6. `orderRepository.save` (PG, with `@Version` optimistic lock)

If step 3 succeeds but a later PG step (e.g. step 6's optimistic-lock collision) throws, `@Transactional` rolls back **only the PG writes**. The Redis ops from step 3 are not undone. Result:

- Order rolled back to `PENDING_PAYMENT`.
- PG `available_stock` rolled back to pre-decrement.
- Redis reservation hash + ZSET entry **gone** (already DELed in step 3).
- Redis stock counter still decremented.

**ReservationSweeper cannot recover this** — it polls the ZSET for expired entries, and there's no entry left for it to find. The stock is silently lost from the Redis counter, and the order's `reservation_id` references a UUID that no longer exists in Redis. On user retry, `decrementPgStockForFlashSaleLines` runs again and is correct, `commitReservation` no-ops (hash already gone) and is correct — so a retry recovers consistency. But if the user *never* retries, the stock leak is permanent until an operator reloads the sale.

The cancel path has the symmetric problem in mirror form: if `releaseReservation` succeeds (stock returned, hash DELed) and then a later step throws, PG rolls back but Redis stays released.

### Possible solutions

1. **Reorder so Redis writes happen last**
   - Move all PG mutations to the start; do Redis ops only after `orderRepository.save` is going to succeed.
   - Pros: shrinks the window dramatically. A Redis failure at the end leaves PG correct (order confirmed) and Redis bookkeeping stale; the sweeper would later try to release but should be taught to check "is this reservation tied to a CONFIRMED order?" before doing so.
   - Cons: still not atomic — a crash between tx commit and the Redis call leaves the same problem, just shifted.

2. **Outbox pattern**
   - Inside the PG tx, INSERT a row into an `outbox_events` table describing the Redis op to perform (commit reservation X, clear cart for user Y).
   - A separate worker (cron, Trigger.dev, listen/notify, Debezium) drains the outbox, runs the Redis op, deletes the row on success, retries on failure.
   - Pros: atomic with the PG state change — either both happen or neither. The canonical fix.
   - Cons: adds an outbox table, a drain worker, and observable lag between order confirmation and Redis cleanup.

3. **Reconciler job**
   - Periodic job scans `orders` for inconsistencies: `CONFIRMED` order whose flash-sale lines still have live reservation hashes (orphan), or `PENDING_PAYMENT` order whose reservation hashes are missing (silent commit). Fix each accordingly.
   - Pros: catches any drift regardless of how it happened (covers crashes, network partitions, manual ops mistakes).
   - Cons: detective control, not preventive. Acceptable as a backstop alongside #1 or #2; not strong enough alone.

4. **Retry with idempotency at the Redis layer**
   - Wrap each Redis call in a small retry-with-backoff. If it still fails, log + page rather than throwing into the tx.
   - Pros: closes most transient-failure windows without architectural change.
   - Cons: doesn't help if the failure is a later *PG* step after Redis already succeeded — which is exactly the case described above.

### Decision

Defer. #2 (outbox) is the right answer for a real system and would also subsume parts of the message-queue TODO above. #1 is a cheap mitigation that makes the window narrower without solving it. #3 is a sensible long-term backstop regardless. Not blocking for the demo because retries do recover consistency; the failure mode is "silent stock leak until operator notices," which is acceptable in a demo but not in production.

