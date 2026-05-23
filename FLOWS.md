# Order flows

Two checkout paths share controllers, idempotency, and persistence — but use different reservation primitives.

- **Normal items** → PG row lock (`SELECT FOR UPDATE`) decrements `available_stock` at checkout.
- **Flash-sale items** → Redis Lua script atomically decrements `flashsale:stock:{pid}` at checkout; PG is only touched at confirm.

A single cart can mix both; each line picks its path based on whether `flashsale:stock:{pid}` exists in Redis.

## Normal-item flow (PG-locked reservation)

```mermaid
sequenceDiagram
    autonumber
    actor U as User (X-User-Id)
    participant API as OrderController
    participant OS as OrderService
    participant Idem as Redis idem:checkout:*
    participant Cart as Redis cart:{userId}
    participant OP as OrderPersistence (TX)
    participant Inv as InventoryService
    participant PG as PostgreSQL

    U->>API: POST /orders/checkout (Idempotency-Key)
    API->>OS: checkout(userId, key)
    OS->>Idem: GET idem:checkout:{key}
    Idem-->>OS: miss
    OS->>Cart: HGETALL cart:{userId}
    Cart-->>OS: items
    OS->>OP: createOrderWithPayment(...)
    activate OP
    OP->>Inv: reserveNormalStock(qtyMap)
    Inv->>PG: SELECT ... FOR UPDATE on inventory rows
    Inv->>PG: UPDATE inventory SET available_stock -= qty
    OP->>PG: INSERT orders (PENDING_PAYMENT) + order_items
    OP->>PG: INSERT payments (PENDING)
    deactivate OP
    OS->>Idem: SET idem:checkout:{key} = response (TTL 24h)
    OS-->>U: 200 OrderDto

    Note over U,PG: --- later ---

    U->>API: POST /orders/{id}/confirm-payment
    API->>OS: confirmPayment
    OS->>PG: UPDATE payments SET status=SUCCESS
    Note right of OS: PG stock already decremented<br/>at checkout — no-op here
    OS->>PG: UPDATE orders SET status=CONFIRMED (version++)
    OS->>Cart: DEL cart:{userId}
    OS-->>U: 200 OrderDto

    Note over U,PG: --- OR cancel ---

    U->>API: POST /orders/{id}/cancel
    API->>OS: cancel
    OS->>PG: UPDATE payments SET status=FAILED
    OS->>Inv: restoreNormalStock(qtyMap)
    Inv->>PG: SELECT ... FOR UPDATE + UPDATE inventory SET available_stock += qty
    OS->>PG: UPDATE orders SET status=CANCELLED
    OS-->>U: 200 OrderDto
```

## Flash-sale flow (Redis-gated reservation)

```mermaid
sequenceDiagram
    autonumber
    actor Admin
    actor U as User (X-User-Id)
    participant API as Controllers
    participant Inv as InventoryService
    participant Redis as Redis
    participant OS as OrderService
    participant OP as OrderPersistence (TX)
    participant Sweep as ReservationSweeper
    participant PG as PostgreSQL

    Admin->>API: POST /inventory/flash-sale/load/{productId}
    API->>Inv: loadFlashSaleStock
    Inv->>Redis: SET flashsale:stock:{pid} = N

    U->>API: POST /orders/checkout (Idempotency-Key)
    API->>OS: checkout
    OS->>Redis: GET idem:checkout:{key} -> miss
    OS->>Redis: HGETALL cart:{userId}
    loop each flash-sale line
        OS->>Inv: reserveFlashSale(pid, qty)
        Inv->>Redis: EVAL reserve_stock.lua<br/>(DECRBY stock, HSET reservation, ZADD expiry)
        Redis-->>Inv: 1 = OK / 0 = INSUFFICIENT_STOCK
    end
    OS->>OP: createOrderWithPayment(reservations)
    activate OP
    OP->>PG: INSERT orders + items (reservation_id per line)
    OP->>PG: INSERT payments (PENDING)
    deactivate OP
    OS->>Redis: SET idem:checkout:{key} = response (TTL 24h)
    OS-->>U: 200 OrderDto

    Note over U,PG: --- confirm ---

    U->>API: POST /orders/{id}/confirm-payment
    API->>OS: confirmPayment
    OS->>PG: UPDATE payments SET status=SUCCESS
    OS->>Inv: decrementPgStockForFlashSaleLines
    Inv->>PG: SELECT FOR UPDATE + UPDATE inventory.available_stock -= qty
    loop each flash-sale line
        OS->>Inv: commitReservation(rid)
        Inv->>Redis: EVAL commit_reservation.lua<br/>(DEL reservation, ZREM expiry)
        Note right of Redis: stock counter stays decremented
    end
    OS->>PG: UPDATE orders SET status=CONFIRMED
    OS->>Redis: DEL cart:{userId}
    OS-->>U: 200 OrderDto

    Note over U,PG: --- OR cancel ---

    U->>API: POST /orders/{id}/cancel
    API->>OS: cancel
    OS->>PG: UPDATE payments SET status=FAILED
    loop each flash-sale line
        OS->>Inv: releaseReservation(rid, pid, qty)
        Inv->>Redis: EVAL release_stock.lua<br/>(INCRBY stock, DEL reservation, ZREM expiry)
    end
    OS->>PG: UPDATE orders SET status=CANCELLED
    OS-->>U: 200 OrderDto

    Note over U,PG: --- abandoned (no confirm, no cancel) ---

    Sweep->>Redis: ZRANGEBYSCORE expiry 0 now
    loop expired reservation
        Sweep->>Redis: EVAL release_stock.lua (returns stock)
    end
```

## Key invariants

- **Normal flow** treats PG `available_stock` as the reservation primitive (decrement at checkout, restore on cancel, no-op on confirm).
- **Flash-sale flow** treats Redis as the reservation primitive (decrement at checkout) and PG as post-confirm truth (decrement at confirm, never reversed). The sweeper closes the abandonment hole that PG row locks naturally don't have.
- Idempotency cache wraps both flows identically — same `Idempotency-Key` twice returns the same `OrderDto`.
- `@Version` on `orders` plus `PENDING_PAYMENT`-only state guard prevents double-confirm / confirm+cancel races.
