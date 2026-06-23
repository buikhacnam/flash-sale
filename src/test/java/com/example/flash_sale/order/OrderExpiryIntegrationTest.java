package com.example.flash_sale.order;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.cart.AddItemRequest;
import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
import com.example.flash_sale.payment.PaymentRepository;
import com.example.flash_sale.payment.PaymentStatus;
import com.example.flash_sale.support.FlashSaleTestData;
import com.example.flash_sale.support.TestCleanupSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderExpiryIntegrationTest {

    @Autowired
    OrderService orderService;
    @Autowired
    CartService cartService;
    @Autowired
    InventoryService inventoryService;
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OrderExpirySweeper orderExpirySweeper;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        TestCleanupSupport.clearCarts(cartService, 1L, 2L, 3L, 4L, 5L);
        TestCleanupSupport.clearFlashSaleRedisState(redis, 1L);
        TestCleanupSupport.clearCheckoutIdempotencyKeys(redis);
    }

    @Test
    void confirm_payment_rejects_pending_order_that_is_past_expires_at_for_flash_sale() {
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 1L, new BigDecimal("99.99"));
        inventoryService.loadFlashSaleStock(1L);
        cartService.addItem(1L, new AddItemRequest(1L, 3));

        OrderDto order = orderService.checkout(1L, UUID.randomUUID().toString());
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("7");

        expireOrder(order.id());

        assertThatThrownBy(() -> orderService.confirmPayment(1L, order.id()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

        Order persisted = orderRepository.findById(order.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(paymentRepository.findByOrderId(order.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("10");
    }

    @Test
    void confirm_payment_rejects_pending_order_that_is_past_expires_at_for_normal_stock() {
        cartService.addItem(2L, new AddItemRequest(5L, 4));

        int before = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(2L, UUID.randomUUID().toString());
        assertThat(inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock())
                .isEqualTo(before - 4);

        expireOrder(order.id());

        assertThatThrownBy(() -> orderService.confirmPayment(2L, order.id()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

        Order persisted = orderRepository.findById(order.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(paymentRepository.findByOrderId(order.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock())
                .isEqualTo(before);
    }

    @Test
    void sweeper_expires_only_due_orders() {
        cartService.addItem(3L, new AddItemRequest(6L, 2));
        OrderDto dueOrder = orderService.checkout(3L, UUID.randomUUID().toString());

        cartService.addItem(4L, new AddItemRequest(7L, 2));
        OrderDto futureOrder = orderService.checkout(4L, UUID.randomUUID().toString());

        Order due = orderRepository.findById(dueOrder.id()).orElseThrow();
        due.setExpiresAt(Instant.now().minusSeconds(5));
        orderRepository.save(due);

        Order future = orderRepository.findById(futureOrder.id()).orElseThrow();
        future.setExpiresAt(Instant.now().plusSeconds(3600));
        orderRepository.save(future);

        orderExpirySweeper.sweep();

        assertThat(orderRepository.findById(dueOrder.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(orderRepository.findById(futureOrder.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    private void expireOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setExpiresAt(Instant.now().minusSeconds(5));
        orderRepository.save(order);
    }
}
