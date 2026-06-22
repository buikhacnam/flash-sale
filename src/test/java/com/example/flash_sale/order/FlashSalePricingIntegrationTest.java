package com.example.flash_sale.order;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.cart.AddItemRequest;
import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.inventory.Inventory;
import com.example.flash_sale.inventory.InventoryFlashSaleConfigRequest;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
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
        cartService.clear(1L);
        redis.delete(InventoryService.stockKey(1L));
        redis.delete(InventoryService.expiryZsetKey());
        Set<String> idemKeys = redis.keys("idem:checkout:*");
        if (idemKeys != null && !idemKeys.isEmpty()) {
            redis.delete(idemKeys);
        }

        Inventory inventory = inventoryRepository.findByProductId(1L).orElseThrow();
        inventory.setFlashSaleStartsAt(null);
        inventory.setFlashSaleEndsAt(null);
        inventory.setFlashSalePrice(null);
        inventoryRepository.save(inventory);
    }

    @Test
    void checkout_persists_flash_sale_price_on_order_and_line_items() {
        Instant now = Instant.now();
        BigDecimal flashSalePrice = new BigDecimal("99.99");
        inventoryService.updateFlashSaleConfigs(
                1L,
                new InventoryFlashSaleConfigRequest(now.minusSeconds(30), now.plusSeconds(300), flashSalePrice)
        );
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
