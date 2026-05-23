package com.example.flash_sale.inventory;

import java.util.UUID;

public record Reservation(UUID reservationId, Long productId, int quantity) {
}
