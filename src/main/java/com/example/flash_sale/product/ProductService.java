package com.example.flash_sale.product;

import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public ProductService(ProductRepository productRepository,
                          StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          @Value("${flashsale.product.cache.ttl-seconds}") long ttlSeconds) {
        this.productRepository = productRepository;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Transactional(readOnly = true)
    public List<ProductDto> list() {
        return productRepository.findAll().stream().map(ProductDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductDto getById(Long id) {
        String key = cacheKey(id);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, ProductDto.class);
            } catch (JsonProcessingException ignored) {
                // poisoned cache value — fall through to DB
                redis.delete(key);
            }
        }
        ProductDto dto = productRepository.findById(id)
                .map(ProductDto::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found", Map.of("productId", id)));
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(dto), ttl);
        } catch (JsonProcessingException ignored) {
        }
        return dto;
    }

    public static String cacheKey(Long productId) {
        return "product:" + productId;
    }
}
