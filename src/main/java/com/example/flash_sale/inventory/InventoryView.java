package com.example.flash_sale.inventory;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryView(Instant flashSaleStartsAt, Instant flashSaleEndsAt, BigDecimal flashSalePrice,
                            Integer flashSaleStock, Integer availableStock) {

    public InventoryView(Instant flashSaleStartsAt, Instant flashSaleEndsAt, BigDecimal flashSalePrice, Integer flashSaleStock, Integer availableStock) {
        this.flashSaleStartsAt = flashSaleStartsAt;
        this.flashSaleEndsAt = flashSaleEndsAt;
        this.flashSalePrice = flashSalePrice;
        this.flashSaleStock = flashSaleStock;
        this.availableStock = availableStock;
    }

    public static InventoryView from(Inventory inventory) {
        return new InventoryView(inventory.getFlashSaleStartsAt(), inventory.getFlashSaleEndsAt(), inventory.getFlashSalePrice(), inventory.getAvailableStock(), inventory.getAvailableStock());
    }
}
