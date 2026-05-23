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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        CartService cartService,
                        InventoryService inventoryService,
                        PaymentService paymentService,
                        UserService userService,
                        OrderPersistenceService orderPersistenceService,
                        StringRedisTemplate redis,
                        ObjectMapper objectMapper,
                        @Value("${flashsale.idempotency.ttl-seconds}") long idempotencyTtlSeconds) {
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

    /**
     * Checkout flow:
     * 1. Idempotency check — replay cached response if Idempotency-Key has been seen.
     * 2. Validate cart + load product prices.
     * 3. For each line that has a loaded flash-sale stock key, atomically reserve in Redis.
     *    Roll back successful reservations if a later line fails.
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
            for (CartItemDto item : cart.items()) {
                if (inventoryService.hasFlashSaleStockKey(item.productId())) {
                    Reservation r = inventoryService.reserveFlashSale(userId, item.productId(), item.quantity());
                    reserved.add(r);
                }
            }
            Order persisted = orderPersistenceService.createOrderWithPayment(userId, cart, productsById, reserved);
            OrderDto dto = OrderDto.from(persisted);
            writeIdempotent(idemKey, dto);
            return dto;
        } catch (RuntimeException ex) {
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
        Order order = requireOwnedOrder(userId, orderId);
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return OrderDto.from(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Order cannot be confirmed in its current state",
                    Map.of("status", order.getStatus().name()));
        }
        paymentService.markSuccess(orderId);
        // Only flash-sale lines need a PG decrement here — normal lines were decremented at checkout.
        inventoryService.decrementPgStockForFlashSaleLines(qtyByProduct(order, true));
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
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderDto.from(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Order cannot be cancelled in its current state",
                    Map.of("status", order.getStatus().name()));
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

    @Transactional(readOnly = true)
    public OrderDto get(Long userId, Long orderId) {
        return OrderDto.from(requireOwnedOrder(userId, orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderDto> listForUser(Long userId) {
        userService.requireUser(userId);
        return orderRepository.findAllByUserIdOrderByIdDesc(userId).stream().map(OrderDto::from).toList();
    }

    private Order requireOwnedOrder(Long userId, Long orderId) {
        userService.requireUser(userId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND,
                        "Order not found", Map.of("orderId", orderId)));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.ORDER_NOT_OWNED,
                    "Order belongs to another user", Map.of("orderId", orderId));
        }
        return order;
    }

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

    private Map<Long, Product> loadProducts(List<CartItemDto> items) {
        List<Long> ids = items.stream().map(CartItemDto::productId).toList();
        Map<Long, Product> map = new HashMap<>();
        for (Product p : productRepository.findAllByIdIn(ids)) {
            map.put(p.getId(), p);
        }
        for (Long id : ids) {
            if (!map.containsKey(id)) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found", Map.of("productId", id));
            }
        }
        return map;
    }

    // ----- Idempotency cache. Stores full response so a retry returns byte-identical output. -----

    private static String idempotencyKeyOf(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Missing Idempotency-Key header");
        }
        return "idem:checkout:" + requestId;
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
}
