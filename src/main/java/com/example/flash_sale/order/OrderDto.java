package com.example.flash_sale.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderDto(Long id,
                       Long userId,
                       OrderStatus status,
                       BigDecimal totalAmount,
                       List<OrderItemDto> items,
                       Instant createdAt,
                       Instant updatedAt) {

    public record OrderItemDto(Long productId,
                               int quantity,
                               BigDecimal unitPrice,
                               BigDecimal lineTotal,
                               UUID reservationId) {
    }

    public static OrderDto from(Order o) {
        List<OrderItemDto> items = o.getItems().stream()
                .map(i -> new OrderItemDto(i.getProductId(), i.getQuantity(),
                        i.getUnitPrice(), i.getLineTotal(), i.getReservationId()))
                .toList();
        return new OrderDto(o.getId(), o.getUserId(), o.getStatus(), o.getTotalAmount(),
                items, o.getCreatedAt(), o.getUpdatedAt());
    }
}
