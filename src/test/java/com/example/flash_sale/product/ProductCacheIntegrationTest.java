package com.example.flash_sale.product;

import com.example.flash_sale.TestcontainersConfiguration;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductCacheIntegrationTest {

    @Autowired
    ProductService productService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    InventoryService inventoryService;

    @BeforeEach
    void clean() {
        redis.delete(ProductService.cacheKey(1L));
        TestCleanupSupport.clearFlashSaleRedisState(redis, 1L);
        TestCleanupSupport.resetFlashSaleConfig(inventoryRepository, 1L);
    }

    @Test
    void cache_miss_then_hit() {
        String key = ProductService.cacheKey(1L);
        assertThat(redis.hasKey(key)).isFalse();

        ProductDto first = productService.getById(1L);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(redis.hasKey(key)).isTrue();

        ProductDto second = productService.getById(1L);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void cache_miss_when_flash_sale_config_is_update() {
        String key = ProductService.cacheKey(1L);

        ProductDto first = productService.getById(1L);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(redis.hasKey(key)).isTrue();

        FlashSaleTestData.configureActiveFlashSale(inventoryService, 1L, new BigDecimal("89.99"));
        inventoryService.loadFlashSaleStock(1L);
        assertThat(redis.hasKey(key)).isFalse();
        ProductDto productHavingFlashSale = productService.getById(1L);
        assertThat(productHavingFlashSale.flashSalePrice()).isEqualTo(new BigDecimal("89.99"));
        assertThat(redis.hasKey(key)).isTrue();

    }


}
