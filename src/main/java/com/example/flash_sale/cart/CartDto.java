package com.example.flash_sale.cart;

import java.util.List;

public record CartDto(Long userId, List<CartItemDto> items) {
}
