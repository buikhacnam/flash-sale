package com.example.flash_sale.product;

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
class ProductCacheIntegrationTest {

    @Autowired
    ProductService productService;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        redis.delete(ProductService.cacheKey(1L));
    }

    @Test
    void cache_miss_then_hit() {
        String key = ProductService.cacheKey(1L);
        assertThat(redis.hasKey(key)).isFalse();

        ProductDto first = productService.getById(1L);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(redis.hasKey(key)).isTrue();

        ProductDto second = productService.getById(1L);
        assertThat(second).isEqualTo(first);
    }
}
