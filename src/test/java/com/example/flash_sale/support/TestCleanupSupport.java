package com.example.flash_sale.support;

import com.example.flash_sale.cart.CartService;
import com.example.flash_sale.inventory.Inventory;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class TestCleanupSupport {

    private TestCleanupSupport() {
    }

    public static void clearCarts(CartService cartService, long... userIds) {
        for (long userId : userIds) {
            cartService.clear(userId);
        }
    }

    public static void clearFlashSaleRedisState(StringRedisTemplate redis, Long productId) {
        redis.delete(InventoryService.stockKey(productId));
        redis.delete(InventoryService.expiryZsetKey());
    }

    public static void clearCheckoutIdempotencyKeys(StringRedisTemplate redis) {
        var keys = redis.keys("idem:checkout:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    public static void resetFlashSaleConfig(InventoryRepository inventoryRepository, Long productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId).orElseThrow();
        inventory.setFlashSaleStartsAt(null);
        inventory.setFlashSaleEndsAt(null);
        inventory.setFlashSalePrice(null);
        inventoryRepository.save(inventory);
    }
}
