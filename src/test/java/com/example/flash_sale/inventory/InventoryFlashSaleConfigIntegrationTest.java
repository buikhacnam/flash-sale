package com.example.flash_sale.inventory;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InventoryFlashSaleConfigIntegrationTest {

    @Autowired
    InventoryService inventoryService;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void clean() {
        redis.delete(InventoryService.stockKey(1L));
        Inventory inventory = inventoryRepository.findByProductId(1L).orElseThrow();
        inventory.setFlashSaleStartsAt(null);
        inventory.setFlashSaleEndsAt(null);
        inventory.setFlashSalePrice(null);
        inventoryRepository.save(inventory);
    }

    @Test
    void load_flash_sale_stock_without_time_window_returns_api_exception() {
        assertThatThrownBy(() -> inventoryService.loadFlashSaleStock(1L))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.FLASH_SALE_NOT_LOADED);
    }

    @Test
    void load_flash_sale_stock_sets_redis_ttl_when_window_is_active() {
        Instant now = Instant.now();
        inventoryService.updateFlashSaleConfigs(
                1L,
                new InventoryFlashSaleConfigRequest(now.minusSeconds(30), now.plusSeconds(120), new BigDecimal("99.99"))
        );

        int loaded = inventoryService.loadFlashSaleStock(1L);

        assertThat(loaded).isEqualTo(10);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(1L))).isEqualTo("10");
        Long ttlSeconds = redis.getExpire(InventoryService.stockKey(1L));
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isBetween(1L, 120L);
    }

    @Test
    void update_endpoint_rejects_missing_flash_sale_fields() throws Exception {
        mockMvc.perform(put("/api/inventory/1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "flashSaleStartsAt": "2026-06-22T10:00:00Z",
                                  "flashSalePrice": 99.99
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields.flashSaleEndsAt").exists());
    }
}
