package com.example.flash_sale.order;

import com.example.flash_sale.cart.CartDto;
import com.example.flash_sale.cart.CartItemDto;
import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.InventoryService;
import com.example.flash_sale.inventory.Reservation;
import com.example.flash_sale.payment.PaymentService;
import com.example.flash_sale.product.Product;
import com.example.flash_sale.product.ProductRepository;
import com.example.flash_sale.user.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CartService cartService;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final UserService userService;
    private final OrderPersistenceService orderPersistenceService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration idempotencyTtl;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, CartService cartService, InventoryService inventoryService, PaymentService paymentService, UserService userService, OrderPersistenceService orderPersistenceService, StringRedisTemplate redis, ObjectMapper objectMapper, @Value("${flashsale.idempotency.ttl-seconds}") long idempotencyTtlSeconds) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.cartService = cartService;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.userService = userService;
        this.orderPersistenceService = orderPersistenceService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.idempotencyTtl = Duration.ofSeconds(idempotencyTtlSeconds);
    }

    private static String idempotencyKeyOf(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Missing Idempotency-Key header");
        }
        return "idem:checkout:" + requestId;
    }

    /**
     * Checkout flow:
     * 1. Idempotency check — replay cached response if Idempotency-Key has been seen.
     * 2. Validate cart + load product prices.
     * 3. For each line that has a loaded flash-sale stock key, atomically reserve in Redis.
     * Roll back successful reservations if a later line fails.
     * 4. Persist order + pending payment in a single DB tx.
     * 5. Cache full response under the idempotency key for replay within TTL.
     */
    public OrderDto checkout(Long userId, String idempotencyKey) {
        userService.requireUser(userId);
        String idemKey = idempotencyKeyOf(idempotencyKey);
        OrderDto cached = readIdempotent(idemKey);
        if (cached != null) {
            return cached;
        }
        CartDto cart = cartService.get(userId);
        if (cart.items().isEmpty()) {
            throw new ApiException(ErrorCode.CART_EMPTY, "Cart is empty", Map.of("userId", userId));
        }

        Map<Long, Product> productsById = loadProducts(cart.items());
        List<Reservation> reserved = new ArrayList<>();
        try {
            // Presence of flashsale:stock:{productId} is the signal that a product is in an active
            // sale — admin's loadFlashSaleStock is what creates that key. No key = treat as normal,
            // gate via PG row lock inside the order-persist tx.
            for (CartItemDto item : cart.items()) {
                if (inventoryService.hasFlashSaleStockKey(item.productId())) {
                    //inside this condition means it is still in flash sale time window (as the key would disappear outside the time window)
                    Reservation r = inventoryService.reserveFlashSale(userId, item.productId(), item.quantity());
                    reserved.add(r);
                }
            }
            Order persisted = orderPersistenceService.createOrderWithPayment(userId, cart, productsById, reserved);
            OrderDto dto = OrderDto.from(persisted);
            writeIdempotent(idemKey, dto);
            return dto;
        } catch (RuntimeException ex) {
            // Reservations succeeded but the DB tx (or a later line) failed: hand the Redis stock back
            // immediately rather than waiting for the 10-min TTL + sweeper. Swallow secondary errors
            // because the original exception is what the user needs to see.
            for (Reservation r : reserved) {
                try {
                    inventoryService.releaseReservation(r.reservationId(), r.productId(), r.quantity());
                } catch (RuntimeException ignored) {
                }
            }
            throw ex;
        }
    }

    @Transactional
    public OrderDto confirmPayment(Long userId, Long orderId) {
        // this acts as the open webhook for payment.
        // ...validation stuff done
        // ...
        Order order = requireOwnedOrder(userId, orderId);

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return OrderDto.from(order);
        }
        if (isExpiredPending(order)) {
            orderPersistenceService.expiredOrder(orderId);
            // TODO: if the PSP captured funds after expiry, trigger refund/void reconciliation here.
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order payment arrived after expiry", Map.of("status", OrderStatus.EXPIRED.name()));
        }
        if (order.getStatus() == OrderStatus.EXPIRED) {
            // TODO: if the PSP captured funds after expiry, trigger refund/void reconciliation here.
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order payment arrived after expiry", Map.of("status", order.getStatus().name()));
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order cannot be confirmed in its current state", Map.of("status", order.getStatus().name()));
        }
        paymentService.markSuccess(orderId);
        // PG was deliberately not touched at checkout for flash-sale lines (the hot row would have
        // serialised the whole sale on a row lock). Catch up now, inside the confirm tx, while the
        // payment success makes this write meaningful.
        inventoryService.decrementPgStockForFlashSaleLines(qtyByProduct(order, true));
        // Commit (not release) the Redis reservation: keys go away but the stock counter stays
        // decremented because that quantity is now permanently sold.
        for (OrderItem item : order.getItems()) {
            if (item.getReservationId() != null) {
                inventoryService.commitReservation(item.getReservationId());
            }
        }
        order.markConfirmed();
        cartService.clear(userId);
        return OrderDto.from(orderRepository.save(order));
    }

    @Transactional
    public OrderDto cancel(Long userId, Long orderId) {
        Order order = requireOwnedOrder(userId, orderId);
        if (isExpiredPending(order)) {
            return orderPersistenceService.expiredOrder(orderId);
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderDto.from(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order cannot be cancelled in its current state", Map.of("status", order.getStatus().name()));
        }
        try {
            paymentService.markFailed(orderId);
        } catch (ApiException ignored) {
        }
        // Flash-sale lines: return stock to Redis. Normal lines: return stock to PG.
        for (OrderItem item : order.getItems()) {
            if (item.getReservationId() != null) {
                inventoryService.releaseReservation(item.getReservationId(), item.getProductId(), item.getQuantity());
            }
        }
        inventoryService.restoreNormalStock(qtyByProduct(order, false));
        order.markCancelled();
        return OrderDto.from(orderRepository.save(order));
    }

    @Transactional
    public OrderDto get(Long userId, Long orderId) {
        Order order = requireOwnedOrder(userId, orderId);
        if (isExpiredPending(order)) {
            return orderPersistenceService.expiredOrder(orderId);
        }

        return OrderDto.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> listForUser(Long userId) {
        userService.requireUser(userId);
        return orderRepository.findAllByUserIdOrderByIdDesc(userId).stream().map(OrderDto::from).toList();
    }

    private Order requireOwnedOrder(Long userId, Long orderId) {
        userService.requireUser(userId);
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found", Map.of("orderId", orderId)));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.ORDER_NOT_OWNED, "Order belongs to another user", Map.of("orderId", orderId));
        }
        return order;
    }

    /**
     * Collapse order items into a productId → total-qty map, filtered by line type.
     * A line is "flash-sale" iff reservation_id is non-null — that UUID is only set when checkout
     * went through the Redis reserve path. The boolean argument is a selector, not a toggle:
     * true  → include only flash-sale lines (used by confirmPayment for the PG decrement)
     * false → include only normal lines    (used by cancel for the PG restore)
     * The equality check (flashSaleLinesOnly == isFlashSale) is just "does this line match the filter".
     */
    private Map<Long, Integer> qtyByProduct(Order order, boolean flashSaleLinesOnly) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            boolean isFlashSale = item.getReservationId() != null;
            if (flashSaleLinesOnly == isFlashSale) {
                map.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }
        }
        return map;
    }

    // Idempotency: store the full response, not just the orderId. A retrying client must see the
    // same payload (same id, same totals, same items) without us re-running checkout — if we cached
    // only the id we'd still have to re-query and might race a concurrent cancel/confirm.

    private Map<Long, Product> loadProducts(List<CartItemDto> items) {
        List<Long> ids = items.stream().map(CartItemDto::productId).toList();
        Map<Long, Product> map = new HashMap<>();
        for (Product p : productRepository.findAllByIdIn(ids)) {
            map.put(p.getId(), p);
        }
        for (Long id : ids) {
            if (!map.containsKey(id)) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found", Map.of("productId", id));
            }
        }
        return map;
    }

    private OrderDto readIdempotent(String key) {
        String cached = redis.opsForValue().get(key);
        if (cached == null) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, OrderDto.class);
        } catch (JsonProcessingException e) {
            redis.delete(key);
            return null;
        }
    }

    private void writeIdempotent(String key, OrderDto dto) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(dto), idempotencyTtl);
        } catch (JsonProcessingException ignored) {
        }
    }

    private boolean isExpiredPending(Order order) {
        Instant expiresAt = order.getExpiresAt();
        return order.getStatus() == OrderStatus.PENDING_PAYMENT && expiresAt != null && !expiresAt.isAfter(Instant.now());
    }
}
