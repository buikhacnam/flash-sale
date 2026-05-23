package com.example.flash_sale.inventory;

import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserveScript;
    private final RedisScript<Long> releaseScript;
    private final RedisScript<Long> commitScript;
    private final long reservationTtlSeconds;

    public InventoryService(InventoryRepository inventoryRepository,
                            StringRedisTemplate redis,
                            RedisScript<Long> reserveStockScript,
                            RedisScript<Long> releaseStockScript,
                            RedisScript<Long> commitReservationScript,
                            @Value("${flashsale.reservation.ttl-seconds}") long reservationTtlSeconds) {
        this.inventoryRepository = inventoryRepository;
        this.redis = redis;
        this.reserveScript = reserveStockScript;
        this.releaseScript = releaseStockScript;
        this.commitScript = commitReservationScript;
        this.reservationTtlSeconds = reservationTtlSeconds;
    }

    @Transactional(readOnly = true)
    public InventoryView getView(Long productId) {
        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found", Map.of("productId", productId)));
        Integer remaining = readFlashSaleRemaining(productId);
        return new InventoryView(productId, inv.getAvailableStock(), inv.getFlashSaleStock(), remaining);
    }

    @Transactional
    public int loadFlashSaleStock(Long productId) {
        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found", Map.of("productId", productId)));
        Integer configured = inv.getFlashSaleStock();
        if (configured == null) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED,
                    "Product is not configured for flash sale", Map.of("productId", productId));
        }
        redis.opsForValue().set(stockKey(productId), Integer.toString(configured));
        return configured;
    }

    public boolean hasFlashSaleStockKey(Long productId) {
        return Boolean.TRUE.equals(redis.hasKey(stockKey(productId)));
    }

    /**
     * Attempts to reserve flash-sale stock atomically. Returns a Reservation handle on success.
     * Throws INSUFFICIENT_STOCK on contention loss and FLASH_SALE_NOT_LOADED if the stock key
     * is not present (admin hasn't loaded the sale yet).
     */
    public Reservation reserveFlashSale(Long userId, Long productId, int quantity) {
        UUID reservationId = UUID.randomUUID();
        long expiresAtMs = System.currentTimeMillis() + reservationTtlSeconds * 1000L;
        Long result = redis.execute(reserveScript,
                List.of(stockKey(productId), reservationKey(reservationId), expiryZsetKey()),
                Integer.toString(quantity),
                reservationId.toString(),
                Long.toString(userId),
                Long.toString(productId),
                Long.toString(reservationTtlSeconds),
                Long.toString(expiresAtMs));
        if (result == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Reservation script returned null");
        }
        if (result == -1L) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED,
                    "Flash-sale stock not loaded for product", Map.of("productId", productId));
        }
        if (result == 0L) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Not enough flash-sale stock", Map.of("productId", productId, "quantity", quantity));
        }
        return new Reservation(reservationId, productId, quantity);
    }

    /** Release a Redis flash-sale reservation. Idempotent — safe to call from cancel or expiry sweep. */
    public boolean releaseReservation(UUID reservationId, Long productId, int quantity) {
        Long result = redis.execute(releaseScript,
                List.of(stockKey(productId), reservationKey(reservationId), expiryZsetKey()),
                Integer.toString(quantity),
                reservationId.toString());
        return result != null && result == 1L;
    }

    /** Commit a Redis flash-sale reservation: clear keys but do not return stock. */
    public boolean commitReservation(UUID reservationId) {
        Long result = redis.execute(commitScript,
                List.of(reservationKey(reservationId), expiryZsetKey()),
                reservationId.toString());
        return result != null && result == 1L;
    }

    /**
     * Decrement PG available_stock atomically per product line. Called within the order confirm tx.
     * Throws INSUFFICIENT_STOCK if PG would go negative (this is rare for flash-sale items because
     * Redis already gated them, but it's still the source of truth post-sale).
     */
    @Transactional
    public void decrementPgStock(Map<Long, Integer> productIdToQty) {
        if (productIdToQty.isEmpty()) {
            return;
        }
        List<Inventory> rows = inventoryRepository.findByProductIdIn(List.copyOf(productIdToQty.keySet()));
        Map<Long, Inventory> byProduct = rows.stream()
                .collect(java.util.stream.Collectors.toMap(Inventory::getProductId, r -> r));
        for (Map.Entry<Long, Integer> e : productIdToQty.entrySet()) {
            Inventory inv = byProduct.get(e.getKey());
            if (inv == null) {
                throw new ApiException(ErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found", Map.of("productId", e.getKey()));
            }
            int qty = e.getValue();
            if (qty > inv.getAvailableStock()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough stock in PG", Map.of("productId", e.getKey(), "requested", qty));
            }
            inv.decrementAvailable(qty);
        }
    }

    private Integer readFlashSaleRemaining(Long productId) {
        String v = redis.opsForValue().get(stockKey(productId));
        return v == null ? null : Integer.parseInt(v);
    }

    public static String stockKey(Long productId) {
        return "flashsale:stock:" + productId;
    }

    public static String reservationKey(UUID reservationId) {
        return "flashsale:reservation:" + reservationId;
    }

    public static String expiryZsetKey() {
        return "flashsale:reservations:expiry";
    }
}
