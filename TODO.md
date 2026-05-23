# TODO

## Normal-order abandonment — no automatic stock release

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
