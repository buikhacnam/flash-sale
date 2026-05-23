package com.example.flash_sale.cart;

import com.example.flash_sale.common.web.CurrentUser;
import com.example.flash_sale.user.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;
    private final UserService userService;

    public CartController(CartService cartService, UserService userService) {
        this.cartService = cartService;
        this.userService = userService;
    }

    @GetMapping
    public CartDto get(@CurrentUser Long userId) {
        userService.requireUser(userId);
        return cartService.get(userId);
    }

    @PostMapping("/items")
    public CartDto add(@CurrentUser Long userId, @Valid @RequestBody AddItemRequest req) {
        userService.requireUser(userId);
        return cartService.addItem(userId, req);
    }

    @PutMapping("/items/{productId}")
    public CartDto update(@CurrentUser Long userId,
                          @PathVariable Long productId,
                          @Valid @RequestBody UpdateQuantityRequest req) {
        userService.requireUser(userId);
        return cartService.updateQuantity(userId, productId, req);
    }

    @DeleteMapping("/items/{productId}")
    public CartDto remove(@CurrentUser Long userId, @PathVariable Long productId) {
        userService.requireUser(userId);
        return cartService.removeItem(userId, productId);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@CurrentUser Long userId) {
        userService.requireUser(userId);
        cartService.clear(userId);
        return ResponseEntity.noContent().build();
    }
}
