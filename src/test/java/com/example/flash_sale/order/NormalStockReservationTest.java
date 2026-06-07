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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NormalStockReservationTest {

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
        clearIdem();
        redis.delete(InventoryService.stockKey(1L));
        redis.delete(InventoryService.expiryZsetKey());
    }

    @Test
    void checkout_decrements_pg_stock_at_checkout() {
        cartService.clear(2L);
        cartService.addItem(2L, new AddItemRequest(5L, 7)); // non-flash-sale product

        int before = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(2L, UUID.randomUUID().toString());
        int afterCheckout = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        assertThat(afterCheckout).isEqualTo(before - 7);
        assertThat(order.items().get(0).reservationId()).isNull();
        assertThat(order.expiresAt()).isNotNull();
        assertThat(order.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void confirm_does_not_double_decrement_for_normal_lines() {
        cartService.clear(3L);
        clearIdem();
        cartService.addItem(3L, new AddItemRequest(6L, 3));

        int before = inventoryRepository.findByProductId(6L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(3L, UUID.randomUUID().toString());
        orderService.confirmPayment(3L, order.id());
        int after = inventoryRepository.findByProductId(6L).orElseThrow().getAvailableStock();
        assertThat(after).isEqualTo(before - 3);
    }

    @Test
    void cancel_restores_normal_pg_stock() {
        cartService.clear(4L);
        clearIdem();
        cartService.addItem(4L, new AddItemRequest(7L, 4));

        int before = inventoryRepository.findByProductId(7L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(4L, UUID.randomUUID().toString());
        assertThat(inventoryRepository.findByProductId(7L).orElseThrow().getAvailableStock())
                .isEqualTo(before - 4);
        orderService.cancel(4L, order.id());
        assertThat(inventoryRepository.findByProductId(7L).orElseThrow().getAvailableStock())
                .isEqualTo(before);
    }

    @Test
    void insufficient_stock_blocks_checkout() {
        cartService.clear(5L);
        clearIdem();
        cartService.addItem(5L, new AddItemRequest(8L, 999_999));

        assertThatThrownBy(() -> orderService.checkout(5L, UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void expire_pending_normal_order_restores_pg_stock_and_marks_payment_failed() {
        cartService.clear(1L);
        cartService.addItem(1L, new AddItemRequest(5L, 2));

        int before = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(1L, UUID.randomUUID().toString());
        expireNow(order.id());

        OrderDto expired = orderService.expireOrder(order.id());

        assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock()).isEqualTo(before);
        assertThat(paymentRepository.findByOrderId(order.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void expire_pending_mixed_order_releases_both_pg_and_redis_stock() {
        cartService.clear(2L);
        inventoryService.loadFlashSaleStock(1L);
        cartService.addItem(2L, new AddItemRequest(1L, 3));
        cartService.addItem(2L, new AddItemRequest(5L, 2));

        int beforePg = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(2L, UUID.randomUUID().toString());
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("7");
        expireNow(order.id());

        OrderDto expired = orderService.expireOrder(order.id());

        assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock()).isEqualTo(beforePg);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("10");
    }

    @Test
    void confirm_after_expiry_rejects_and_normalizes_to_expired() {
        cartService.clear(3L);
        cartService.addItem(3L, new AddItemRequest(6L, 2));

        OrderDto order = orderService.checkout(3L, UUID.randomUUID().toString());
        expireNow(order.id());

        assertThatThrownBy(() -> orderService.confirmPayment(3L, order.id()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void get_and_list_normalize_stale_pending_orders() {
        cartService.clear(4L);
        cartService.addItem(4L, new AddItemRequest(7L, 1));

        OrderDto order = orderService.checkout(4L, UUID.randomUUID().toString());
        expireNow(order.id());

        OrderDto fetched = orderService.get(4L, order.id());
        List<OrderDto> listed = orderService.listForUser(4L);

        assertThat(fetched.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(listed).extracting(OrderDto::id).contains(order.id());
        assertThat(listed).filteredOn(o -> o.id().equals(order.id()))
                .singleElement()
                .extracting(OrderDto::status)
                .isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void sweeper_ignores_confirmed_and_cancelled_orders() {
        cartService.clear(1L);
        cartService.addItem(1L, new AddItemRequest(5L, 1));
        OrderDto confirmed = orderService.checkout(1L, UUID.randomUUID().toString());
        orderService.confirmPayment(1L, confirmed.id());
        expireNow(confirmed.id());

        cartService.clear(2L);
        cartService.addItem(2L, new AddItemRequest(6L, 1));
        OrderDto cancelled = orderService.checkout(2L, UUID.randomUUID().toString());
        orderService.cancel(2L, cancelled.id());
        expireNow(cancelled.id());

        orderExpirySweeper.sweep();

        assertThat(orderRepository.findById(confirmed.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderRepository.findById(cancelled.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void expiring_twice_is_a_no_op() {
        cartService.clear(5L);
        cartService.addItem(5L, new AddItemRequest(7L, 2));

        int before = inventoryRepository.findByProductId(7L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(5L, UUID.randomUUID().toString());
        expireNow(order.id());

        OrderDto first = orderService.expireOrder(order.id());
        OrderDto second = orderService.expireOrder(order.id());

        assertThat(first.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(second.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(inventoryRepository.findByProductId(7L).orElseThrow().getAvailableStock()).isEqualTo(before);
    }

    private void expireNow(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setExpiresAt(Instant.now().minusSeconds(1));
        orderRepository.save(order);
    }

    private void clearIdem() {
        Set<String> keys = redis.keys("idem:checkout:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
