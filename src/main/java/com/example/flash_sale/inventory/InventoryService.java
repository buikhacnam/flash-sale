package com.example.flash_sale.inventory;

import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import com.example.flash_sale.product.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
    private final long reservationHashRetentionSeconds;

    public InventoryService(InventoryRepository inventoryRepository, StringRedisTemplate redis, RedisScript<Long> reserveStockScript, RedisScript<Long> releaseStockScript, RedisScript<Long> commitReservationScript, @Value("${flashsale.reservation.ttl-seconds}") long reservationTtlSeconds, @Value("${flashsale.reservation.hash-retention-seconds}") long reservationHashRetentionSeconds) {
        this.inventoryRepository = inventoryRepository;
        this.redis = redis;
        this.reserveScript = reserveStockScript;
        this.releaseScript = releaseStockScript;
        this.commitScript = commitReservationScript;
        this.reservationTtlSeconds = reservationTtlSeconds;
        this.reservationHashRetentionSeconds = reservationHashRetentionSeconds;
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

    @Transactional(readOnly = true)
    public InventoryView getView(Long productId) {
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND, "Inventory not found", Map.of("productId", productId)));
        Integer remaining = readFlashSaleRemaining(productId);
        return InventoryView.from(inv, remaining);
    }

    @Transactional
    public InventoryView updateFlashSaleConfigs(Long productId, InventoryFlashSaleConfigRequest inventoryFlashSaleConfigRequest) {
        // update columns related to flash sale (flash_sale_starts_at, flash_sale_ends_at, flash_sale_price)
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND, "Inventory not found", Map.of("productId", productId)));
        // should this allow to be updated during the flash sale is going on?
        //allow for now
        inv.setFlashSalePrice(inventoryFlashSaleConfigRequest.flashSalePrice());
        inv.setFlashSaleStartsAt(inventoryFlashSaleConfigRequest.flashSaleStartsAt());
        inv.setFlashSaleEndsAt(inventoryFlashSaleConfigRequest.flashSaleEndsAt());

        //set TTL for stockKey(productId) if it existed.
        if (hasFlashSaleStockKey(productId)) {
            Duration ttl = Duration.between(Instant.now(), inv.getFlashSaleEndsAt());
            redis.expire(stockKey(productId), ttl); //would delete key when time is in the past
        }

        redis.delete(ProductService.cacheKey(productId));

        return InventoryView.from(inventoryRepository.save(inv));
    }

    @Transactional
    public int loadFlashSaleStock(Long productId) {
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow(() -> new ApiException(ErrorCode.INVENTORY_NOT_FOUND, "Inventory not found", Map.of("productId", productId)));
        Integer configured = inv.getFlashSaleStock();
        if (configured == null) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED, "Product is not configured for flash sale", Map.of("productId", productId));
        }

        //check if the time window has set
        if(inv.getFlashSaleEndsAt() == null || inv.getFlashSaleStartsAt() == null) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED, "Product is not configured time window for flash sale", Map.of("productId", productId));
        }

        //check if the flash-sale time is in the correct window
        if (!isInFlashSale(inv)) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED, "Flash sale window time is not correct", Map.of("productId", productId));
        }

        Duration ttl = Duration.between(Instant.now(), inv.getFlashSaleEndsAt());
        if (ttl.isZero() || ttl.isNegative()) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED, "Flash sale already ended", Map.of("productId", productId));
        }

        redis.opsForValue().set(stockKey(productId), Integer.toString(configured), ttl);
        redis.delete(ProductService.cacheKey(productId));
        return configured;
    }

    /**
     * Presence of the Redis stock key is the source of truth for "is this product in an active
     * flash sale right now". The admin-driven loadFlashSaleStock endpoint creates the key; once
     * deleted (or expired by an operator), checkout falls back to the normal PG-locked path.
     */
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
        long hashTtlSeconds = reservationTtlSeconds + reservationHashRetentionSeconds;
        Long result = redis.execute(reserveScript, List.of(stockKey(productId), reservationKey(reservationId), expiryZsetKey()), Integer.toString(quantity), reservationId.toString(), Long.toString(userId), Long.toString(productId), Long.toString(hashTtlSeconds), Long.toString(expiresAtMs));
        if (result == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Reservation script returned null");
        }
        if (result == -1L) {
            throw new ApiException(ErrorCode.FLASH_SALE_NOT_LOADED, "Flash-sale stock not loaded for product", Map.of("productId", productId));
        }
        if (result == 0L) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough flash-sale stock", Map.of("productId", productId, "quantity", quantity));
        }
        return new Reservation(reservationId, productId, quantity);
    }

    /**
     * Release a Redis flash-sale reservation. Idempotent — safe to call from cancel or expiry sweep.
     */
    public boolean releaseReservation(UUID reservationId, Long productId, int quantity) {
        Long result = redis.execute(releaseScript, List.of(stockKey(productId), reservationKey(reservationId), expiryZsetKey()), Integer.toString(quantity), reservationId.toString());
        return result != null && result == 1L;
    }

    /**
     * Commit a Redis flash-sale reservation: clear keys but do not return stock.
     */
    public boolean commitReservation(UUID reservationId) {
        Long result = redis.execute(commitScript, List.of(reservationKey(reservationId), expiryZsetKey()), reservationId.toString());
        return result != null && result == 1L;
    }

    /**
     * Reserve non-flash-sale stock by decrementing PG available_stock under a row-level write lock.
     * Called from inside the order-persistence tx so the order + payment + stock decrement are atomic.
     * Throws INSUFFICIENT_STOCK if a concurrent checkout drained the row first.
     */
    public void reserveNormalStock(Map<Long, Integer> productIdToQty) {
        if (productIdToQty.isEmpty()) {
            return;
        }
        Map<Long, Inventory> byProduct = lockRowsByProductIds(productIdToQty.keySet());
        for (Map.Entry<Long, Integer> e : productIdToQty.entrySet()) {
            Inventory inv = byProduct.get(e.getKey());
            int qty = e.getValue();
            if (qty > inv.getAvailableStock()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock", Map.of("productId", e.getKey(), "requested", qty, "available", inv.getAvailableStock()));
            }
            inv.decrementAvailable(qty);
        }
    }

    /**
     * Return non-flash-sale stock to PG. Called on order cancel for lines without a Redis reservation.
     */
    @Transactional
    public void restoreNormalStock(Map<Long, Integer> productIdToQty) {
        if (productIdToQty.isEmpty()) {
            return;
        }
        Map<Long, Inventory> byProduct = lockRowsByProductIds(productIdToQty.keySet());
        for (Map.Entry<Long, Integer> e : productIdToQty.entrySet()) {
            byProduct.get(e.getKey()).incrementAvailable(e.getValue());
        }
    }

    /**
     * Decrement PG available_stock for flash-sale lines at confirm time. Lines were gated by the
     * Redis counter at checkout, so insufficient here means a serious drift between Redis and PG.
     */
    public void decrementPgStockForFlashSaleLines(Map<Long, Integer> productIdToQty) {
        if (productIdToQty.isEmpty()) {
            return;
        }
        Map<Long, Inventory> byProduct = lockRowsByProductIds(productIdToQty.keySet());
        for (Map.Entry<Long, Integer> e : productIdToQty.entrySet()) {
            Inventory inv = byProduct.get(e.getKey());
            int qty = e.getValue();
            if (qty > inv.getAvailableStock()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough PG stock at confirm", Map.of("productId", e.getKey(), "requested", qty));
            }
            inv.decrementAvailable(qty);
        }
    }

    public boolean isInFlashSale(Inventory inventory) {
        if(inventory.getFlashSaleStartsAt() == null || inventory.getFlashSaleEndsAt() == null) {
            return false;
        }
        return Instant.now().isAfter(inventory.getFlashSaleStartsAt()) && Instant.now().isBefore(inventory.getFlashSaleEndsAt());
    }

    private Map<Long, Inventory> lockRowsByProductIds(java.util.Set<Long> productIds) {
        List<Inventory> rows = inventoryRepository.findByProductIdIn(List.copyOf(productIds));
        Map<Long, Inventory> byProduct = rows.stream().collect(java.util.stream.Collectors.toMap(Inventory::getProductId, r -> r));
        for (Long id : productIds) {
            if (!byProduct.containsKey(id)) {
                throw new ApiException(ErrorCode.INVENTORY_NOT_FOUND, "Inventory not found", Map.of("productId", id));
            }
        }
        return byProduct;
    }

    private Integer readFlashSaleRemaining(Long productId) {
        String v = redis.opsForValue().get(stockKey(productId));
        return v == null ? null : Integer.parseInt(v);
    }
}
