package com.example.flash_sale.support;

import com.example.flash_sale.inventory.InventoryFlashSaleConfigRequest;
import com.example.flash_sale.inventory.InventoryService;

import java.math.BigDecimal;
import java.time.Instant;

public final class FlashSaleTestData {

    private FlashSaleTestData() {
    }

    public static void configureActiveFlashSale(
            InventoryService inventoryService,
            Long productId,
            BigDecimal flashSalePrice
    ) {
        Instant now = Instant.now();
        inventoryService.updateFlashSaleConfigs(
                productId,
                new InventoryFlashSaleConfigRequest(
                        now.minusSeconds(30),
                        now.plusSeconds(300),
                        flashSalePrice
                )
        );
    }
}
