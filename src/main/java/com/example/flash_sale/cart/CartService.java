package com.example.flash_sale.cart;

import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.product.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CartService {

    private final StringRedisTemplate redis;
    private final ProductRepository productRepository;
    private final Duration ttl;

    public CartService(StringRedisTemplate redis,
                       ProductRepository productRepository,
                       @Value("${flashsale.cart.ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.productRepository = productRepository;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public CartDto get(Long userId) {
        String key = cartKey(userId);
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        List<CartItemDto> items = new ArrayList<>(raw.size());
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            items.add(new CartItemDto(Long.parseLong((String) e.getKey()), Integer.parseInt((String) e.getValue())));
        }
        items.sort((a, b) -> Long.compare(a.productId(), b.productId()));
        return new CartDto(userId, items);
    }

    public CartDto addItem(Long userId, AddItemRequest req) {
        requireProductExists(req.productId());
        String key = cartKey(userId);
        String field = req.productId().toString();
        Object existing = redis.opsForHash().get(key, field);
        int newQty = req.quantity() + (existing == null ? 0 : Integer.parseInt((String) existing));
        redis.opsForHash().put(key, field, Integer.toString(newQty));
        redis.expire(key, ttl);
        return get(userId);
    }

    public CartDto updateQuantity(Long userId, Long productId, UpdateQuantityRequest req) {
        requireProductExists(productId);
        String key = cartKey(userId);
        String field = productId.toString();
        if (!redis.opsForHash().hasKey(key, field)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Item not in cart",
                    Map.of("productId", productId));
        }
        redis.opsForHash().put(key, field, req.quantity().toString());
        redis.expire(key, ttl);
        return get(userId);
    }

    public CartDto removeItem(Long userId, Long productId) {
        String key = cartKey(userId);
        redis.opsForHash().delete(key, productId.toString());
        return get(userId);
    }

    public void clear(Long userId) {
        redis.delete(cartKey(userId));
    }

    private void requireProductExists(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found",
                    Map.of("productId", productId));
        }
    }

    public static String cartKey(Long userId) {
        return "cart:" + userId;
    }
}
