package com.example.flash_sale.order;

import com.example.flash_sale.common.web.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public OrderDto checkout(@CurrentUser Long userId,
                             @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return orderService.checkout(userId, idempotencyKey);
    }

    @PostMapping("/{orderId}/confirm-payment")
    public OrderDto confirm(@CurrentUser Long userId, @PathVariable Long orderId) {
        return orderService.confirmPayment(userId, orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderDto cancel(@CurrentUser Long userId, @PathVariable Long orderId) {
        return orderService.cancel(userId, orderId);
    }

    @GetMapping("/{orderId}")
    public OrderDto get(@CurrentUser Long userId, @PathVariable Long orderId) {
        return orderService.get(userId, orderId);
    }

    @GetMapping
    public List<OrderDto> list(@CurrentUser Long userId) {
        return orderService.listForUser(userId);
    }
}
