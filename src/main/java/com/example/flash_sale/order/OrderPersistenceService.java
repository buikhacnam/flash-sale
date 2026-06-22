package com.example.flash_sale.order;

import com.example.flash_sale.cart.CartDto;
import com.example.flash_sale.cart.CartItemDto;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.Inventory;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
import com.example.flash_sale.inventory.Reservation;
import com.example.flash_sale.payment.PaymentService;
import com.example.flash_sale.payment.PaymentStatus;
import com.example.flash_sale.product.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final InventoryRepository inventoryRepository;
    private final long pendingPaymentTtlSeconds;

    public OrderPersistenceService(OrderRepository orderRepository, PaymentService paymentService, InventoryService inventoryService, InventoryRepository inventoryRepository, @Value("${order.pending-payment.ttl-seconds}") long pendingPaymentTtlSeconds) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.inventoryRepository = inventoryRepository;
        this.pendingPaymentTtlSeconds = pendingPaymentTtlSeconds;
    }

    /**
     * Atomically: lock + decrement PG stock for non-flash-sale lines, persist order + items,
     * create the pending payment row. Flash-sale lines have already been gated in Redis by the caller.
     */
    @Transactional
    public Order createOrderWithPayment(Long userId, CartDto cart, Map<Long, Product> productsById, List<Reservation> reservations) {
        // Split the cart: flash-sale lines were already gated in Redis by the caller and arrive here
        // as Reservation handles; everything else still needs a PG row lock + decrement, which we do
        // inside this tx so the order + payment + stock change commit atomically.
        Map<Long, Reservation> byProduct = new HashMap<>();
        for (Reservation r : reservations) {
            byProduct.put(r.productId(), r);
        }

        Map<Long, Integer> normalQty = new LinkedHashMap<>();
        for (CartItemDto item : cart.items()) {
            if (!byProduct.containsKey(item.productId())) {
                normalQty.merge(item.productId(), item.quantity(), Integer::sum);
            }
        }
        // Called unconditionally — reserveNormalStock no-ops on an empty map, so an all-flash-sale
        // cart skips the PG lock entirely. Cheaper than branching the control flow here.
        inventoryService.reserveNormalStock(normalQty);

        BigDecimal total = BigDecimal.ZERO;
        for (CartItemDto item : cart.items()) {
            Product p = productsById.get(item.productId());
            if (p == null) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found", Map.of("productId", item.productId()));
            }
            BigDecimal price = effectiveUnitPrice(item.productId(), p.getPrice(), byProduct);
            total = total.add(price.multiply(BigDecimal.valueOf(item.quantity())));
        }

        Order order = new Order(userId, total, Instant.now().plusSeconds(pendingPaymentTtlSeconds));
        for (CartItemDto item : cart.items()) {
            Product p = productsById.get(item.productId());
            Reservation r = byProduct.get(item.productId());
            BigDecimal price = effectiveUnitPrice(item.productId(), p.getPrice(), byProduct);
            order.addItem(new OrderItem(p.getId(), item.quantity(), price, r == null ? null : r.reservationId()));
        }
        Order persisted = orderRepository.save(order);
        paymentService.createPending(persisted.getId(), persisted.getTotalAmount());
        return persisted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderDto expiredOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found!"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return OrderDto.from(order);
        }
        //mark associate payment as failed if there is
        if (paymentService.require(orderId).getStatus() == PaymentStatus.PENDING) {
            paymentService.markFailed(orderId);
        }

        //loop through items in the order to release reservation (for flash sale only).
        for (OrderItem item : order.getItems()) {
            if (item.getReservationId() != null) {
                inventoryService.releaseReservation(item.getReservationId(), item.getProductId(), item.getQuantity());
            }
        }

        //restore stock
        inventoryService.restoreNormalStock(qtyByProduct(order, false));

        order.markExpired();
        return OrderDto.from(order);

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

    private BigDecimal effectiveUnitPrice(Long productId, BigDecimal defaultPrice, Map<Long, Reservation> reservationsByProduct) {
        if (!reservationsByProduct.containsKey(productId)) {
            return defaultPrice;
        }
        Optional<Inventory> inventory = inventoryRepository.findByProductId(productId);
        if (inventory.isEmpty() || inventory.get().getFlashSalePrice() == null) {
            return defaultPrice;
        }
        return inventory.get().getFlashSalePrice();
    }
}
