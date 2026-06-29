package com.example.flash_sale.inventory;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryView(Instant flashSaleStartsAt, Instant flashSaleEndsAt, BigDecimal flashSalePrice,
                            Integer flashSaleStock, Integer availableStock, Integer flashSaleStockRemaining) {

    public static InventoryView from(Inventory inventory, Integer flashSaleStockRemaining) {
        return new InventoryView(inventory.getFlashSaleStartsAt(), inventory.getFlashSaleEndsAt(), inventory.getFlashSalePrice(), inventory.getFlashSaleStock(), inventory.getAvailableStock(), flashSaleStockRemaining);
    }
}
