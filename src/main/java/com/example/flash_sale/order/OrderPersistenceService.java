package com.example.flash_sale.order;

import com.example.flash_sale.cart.CartDto;
import com.example.flash_sale.cart.CartItemDto;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.InventoryService;
import com.example.flash_sale.inventory.Reservation;
import com.example.flash_sale.payment.PaymentService;
import com.example.flash_sale.product.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final long pendingPaymentTtlSeconds;

    public OrderPersistenceService(OrderRepository orderRepository,
                                   PaymentService paymentService,
                                   InventoryService inventoryService,
                                   @Value("${orders.pending-payment.ttl-seconds}") long pendingPaymentTtlSeconds) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.pendingPaymentTtlSeconds = pendingPaymentTtlSeconds;
    }

    /**
     * Atomically: lock + decrement PG stock for non-flash-sale lines, persist order + items,
     * create the pending payment row. Flash-sale lines have already been gated in Redis by the caller.
     */
    @Transactional
    public Order createOrderWithPayment(Long userId,
                                        CartDto cart,
                                        Map<Long, Product> productsById,
                                        List<Reservation> reservations) {
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
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found", Map.of("productId", item.productId()));
            }
            total = total.add(p.getPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }

        Order order = new Order(userId, total, Instant.now().plusSeconds(pendingPaymentTtlSeconds));
        for (CartItemDto item : cart.items()) {
            Product p = productsById.get(item.productId());
            Reservation r = byProduct.get(item.productId());
            order.addItem(new OrderItem(p.getId(), item.quantity(), p.getPrice(),
                    r == null ? null : r.reservationId()));
        }
        Order persisted = orderRepository.save(order);
        paymentService.createPending(persisted.getId(), persisted.getTotalAmount());
        return persisted;
    }
}
