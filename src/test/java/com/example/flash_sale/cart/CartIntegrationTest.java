package com.example.flash_sale.cart;

import com.example.flash_sale.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CartIntegrationTest {

    @Autowired
    CartService cartService;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        redis.delete(CartService.cartKey(1L));
    }

    @Test
    void add_then_update_then_remove() {
        cartService.addItem(1L, new AddItemRequest(1L, 2));
        cartService.addItem(1L, new AddItemRequest(2L, 3));

        CartDto cart = cartService.get(1L);
        assertThat(cart.items()).hasSize(2);
        assertThat(cart.items().get(0).quantity()).isEqualTo(2);
        assertThat(cart.items().get(1).quantity()).isEqualTo(3);

        cartService.updateQuantity(1L, 1L, new UpdateQuantityRequest(5));
        assertThat(cartService.get(1L).items().get(0).quantity()).isEqualTo(5);

        cartService.removeItem(1L, 2L);
        assertThat(cartService.get(1L).items()).hasSize(1);
    }

    @Test
    void stored_under_redis_hash_with_ttl() {
        cartService.addItem(1L, new AddItemRequest(1L, 7));

        String key = CartService.cartKey(1L);
        assertThat(redis.opsForHash().get(key, "1")).isEqualTo("7");
        Long ttl = redis.getExpire(key);
        assertThat(ttl).isPositive();
    }
}
