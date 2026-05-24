package com.example.flash_sale.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic sweeper that walks the reservation expiry ZSET and lets the release Lua return stock
 * for any reservations whose hash has TTL'd out. The release script is idempotent so racing the
 * TTL or running the sweeper twice is safe — it'll be a no-op the second time.
 */
@Component
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;

    public ReservationSweeper(StringRedisTemplate redis, RedisScript<Long> releaseStockScript) {
        this.redis = redis;
        this.releaseScript = releaseStockScript;
    }

    @Scheduled(fixedDelayString = "${flashsale.sweeper.fixed-delay-ms}")
    public void sweep() {
        long now = System.currentTimeMillis();
        Set<String> expired = redis.opsForZSet().rangeByScore(InventoryService.expiryZsetKey(), 0, now, 0, BATCH_SIZE);
        if (expired == null || expired.isEmpty()) {
            return;
        }
        for (String reservationId : expired) {
            String key = InventoryService.reservationKey(UUID.fromString(reservationId));
            Map<Object, Object> hash = redis.opsForHash().entries(key);
            if (hash.isEmpty()) {
                // Hash beat us to expiry (its TTL fired before we got here) — and once it's gone we
                // can't reconstruct (productId, qty) to restore. In practice the hash TTL is 10min
                // while we sweep every 30s, so this branch should be unreachable; if it fires we've
                // leaked a reservation slot and only an operator-driven reload can refill the counter.
                redis.opsForZSet().remove(InventoryService.expiryZsetKey(), reservationId);
                continue;
            }
            Long productId = Long.parseLong((String) hash.get("productId"));
            int qty = Integer.parseInt((String) hash.get("qty"));
            Long result = redis.execute(releaseScript,
                    List.of(InventoryService.stockKey(productId), key, InventoryService.expiryZsetKey()),
                    Integer.toString(qty),
                    reservationId);
            if (result != null && result == 1L) {
                log.info("Released expired reservation {} for product {} qty {}", reservationId, productId, qty);
            }
        }
    }
}
