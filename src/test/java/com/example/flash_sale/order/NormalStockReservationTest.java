package com.example.flash_sale.order;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.cart.AddItemRequest;
import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
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
    StringRedisTemplate redis;

    @Test
    void checkout_decrements_pg_stock_at_checkout() {
        cartService.clear(2L);
        cartService.addItem(2L, new AddItemRequest(5L, 7)); // non-flash-sale product

        int before = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        OrderDto order = orderService.checkout(2L, UUID.randomUUID().toString());
        int afterCheckout = inventoryRepository.findByProductId(5L).orElseThrow().getAvailableStock();
        assertThat(afterCheckout).isEqualTo(before - 7);
        assertThat(order.items().get(0).reservationId()).isNull();
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

    private void clearIdem() {
        Set<String> keys = redis.keys("idem:checkout:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
