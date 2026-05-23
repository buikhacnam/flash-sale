package com.example.flash_sale.order;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.cart.AddItemRequest;
import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CheckoutIdempotencyTest {

    @Autowired
    OrderService orderService;

    @Autowired
    CartService cartService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        cartService.clear(1L);
        // Reset flash-sale state for product 1
        redis.delete(InventoryService.stockKey(1L));
        redis.delete(InventoryService.expiryZsetKey());
        Set<String> idemKeys = redis.keys("idem:checkout:*");
        if (idemKeys != null && !idemKeys.isEmpty()) {
            redis.delete(idemKeys);
        }
    }

    @Test
    void duplicate_idempotency_key_returns_same_order() {
        cartService.addItem(1L, new AddItemRequest(4L, 2)); // non-flash-sale product
        String key = UUID.randomUUID().toString();

        OrderDto first = orderService.checkout(1L, key);
        OrderDto second = orderService.checkout(1L, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.totalAmount()).isEqualByComparingTo(first.totalAmount());
    }

    @Test
    void empty_cart_rejected() {
        assertThatThrownBy(() -> orderService.checkout(1L, UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.CART_EMPTY);
    }

    @Test
    void checkout_with_flash_sale_product_creates_reservation_then_confirm_holds_stock() {
        inventoryService.loadFlashSaleStock(1L); // stock 10
        cartService.addItem(1L, new AddItemRequest(1L, 3));

        OrderDto order = orderService.checkout(1L, UUID.randomUUID().toString());
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.items().get(0).reservationId()).isNotNull();
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("7");

        OrderDto confirmed = orderService.confirmPayment(1L, order.id());
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        // Commit does not restore stock
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("7");
        // Cart cleared after confirm
        assertThat(cartService.get(1L).items()).isEmpty();
    }

    @Test
    void cancel_releases_flash_sale_stock() {
        inventoryService.loadFlashSaleStock(1L); // 10
        cartService.addItem(1L, new AddItemRequest(1L, 4));

        OrderDto order = orderService.checkout(1L, UUID.randomUUID().toString());
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("6");

        OrderDto cancelled = orderService.cancel(1L, order.id());
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("10");
    }
}
