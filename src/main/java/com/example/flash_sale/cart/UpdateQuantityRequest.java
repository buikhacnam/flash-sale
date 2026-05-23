package com.example.flash_sale.cart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateQuantityRequest(@NotNull @Min(1) Integer quantity) {
}
