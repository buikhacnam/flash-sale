package com.example.flash_sale.product;

import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.inventory.Inventory;
import com.example.flash_sale.inventory.InventoryRepository;
import com.example.flash_sale.inventory.InventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public ProductService(ProductRepository productRepository,
                          InventoryRepository inventoryRepository,
                          InventoryService inventoryService,
                          StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          @Value("${flashsale.product.cache.ttl-seconds}") long ttlSeconds) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryService = inventoryService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public static String cacheKey(Long productId) {
        return "product:" + productId;
    }

    @Transactional(readOnly = true)
    public List<ProductDto> list() {
        //TODO cache the response and having flashsale price included
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
        Product product = productRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                "Product not found", Map.of("productId", id)));

        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND, "Inventory not found", Map.of("productId", id)));
        BigDecimal flashSalePrice = null;
        if (inventoryService.hasFlashSaleStockKey(product.getId()) && inventoryService.isInFlashSale(inventory)) {
            flashSalePrice = inventory.getFlashSalePrice();
        }
        ProductDto dto = ProductDto.from(product, flashSalePrice);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(dto), ttl);
        } catch (JsonProcessingException ignored) {
        }
        return dto;
    }
}
