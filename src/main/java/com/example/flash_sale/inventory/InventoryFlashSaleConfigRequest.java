package com.example.flash_sale.inventory;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryFlashSaleConfigRequest(
        @NotNull Instant flashSaleStartsAt,
        @NotNull Instant flashSaleEndsAt,
        @NotNull BigDecimal flashSalePrice) {
}
