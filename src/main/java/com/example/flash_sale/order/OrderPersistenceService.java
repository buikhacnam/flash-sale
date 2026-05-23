package com.example.flash_sale.order;

import com.example.flash_sale.cart.CartDto;
import com.example.flash_sale.cart.CartItemDto;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.Reservation;
import com.example.flash_sale.payment.PaymentService;
import com.example.flash_sale.product.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    public OrderPersistenceService(OrderRepository orderRepository, PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
    }

    @Transactional
    public Order createOrderWithPayment(Long userId,
                                        CartDto cart,
                                        Map<Long, Product> productsById,
                                        List<Reservation> reservations) {
        Map<Long, Reservation> byProduct = new HashMap<>();
        for (Reservation r : reservations) {
            byProduct.put(r.productId(), r);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (CartItemDto item : cart.items()) {
            Product p = productsById.get(item.productId());
            if (p == null) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found", Map.of("productId", item.productId()));
            }
            total = total.add(p.getPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }
        Order order = new Order(userId, total);
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
