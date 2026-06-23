package com.example.flash_sale.order;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.cart.AddItemRequest;
import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
import com.example.flash_sale.support.FlashSaleTestData;
import com.example.flash_sale.support.TestCleanupSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlashSalePricingIntegrationTest {

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

    @BeforeEach
    void clean() {
        TestCleanupSupport.clearCarts(cartService, 1L);
        TestCleanupSupport.clearFlashSaleRedisState(redis, 1L);
        TestCleanupSupport.clearCheckoutIdempotencyKeys(redis);
        TestCleanupSupport.resetFlashSaleConfig(inventoryRepository, 1L);
    }

    @Test
    void checkout_persists_flash_sale_price_on_order_and_line_items() {
        BigDecimal flashSalePrice = new BigDecimal("99.99");
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 1L, flashSalePrice);
        inventoryService.loadFlashSaleStock(1L);
        cartService.addItem(1L, new AddItemRequest(1L, 2));

        OrderDto order = orderService.checkout(1L, UUID.randomUUID().toString());

        assertThat(order.totalAmount()).isEqualByComparingTo("199.98");
        assertThat(order.items()).hasSize(1);
        assertThat(order.items().get(0).unitPrice()).isEqualByComparingTo(flashSalePrice);
        assertThat(order.items().get(0).lineTotal()).isEqualByComparingTo("199.98");
        assertThat(order.items().get(0).reservationId()).isNotNull();
    }
}
