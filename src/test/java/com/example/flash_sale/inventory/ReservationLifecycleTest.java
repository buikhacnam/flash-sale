package com.example.flash_sale.inventory;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.support.FlashSaleTestData;
import com.example.flash_sale.support.TestCleanupSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationLifecycleTest {

    @Autowired
    InventoryService inventoryService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ReservationSweeper sweeper;

    @Value("${flashsale.reservation.ttl-seconds}")
    long reservationTtlSeconds;

    @Value("${flashsale.reservation.hash-retention-seconds}")
    long reservationHashRetentionSeconds;

    @BeforeEach
    void cleanRedis() {
        TestCleanupSupport.clearFlashSaleRedisState(redis, 2L);
    }

    @Test
    void release_returns_stock_and_is_idempotent() {
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 2L, new BigDecimal("89.99"));
        inventoryService.loadFlashSaleStock(2L);
        Reservation r = inventoryService.reserveFlashSale(1L, 2L, 3);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("17");

        assertThat(inventoryService.releaseReservation(r.reservationId(), 2L, 3)).isTrue();
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("20");

        // Second release is a no-op (the script returns 0 because the hash is gone).
        assertThat(inventoryService.releaseReservation(r.reservationId(), 2L, 3)).isFalse();
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("20");
    }

    @Test
    void commit_does_not_return_stock() {
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 2L, new BigDecimal("89.99"));
        inventoryService.loadFlashSaleStock(2L);
        Reservation r = inventoryService.reserveFlashSale(1L, 2L, 4);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("16");

        assertThat(inventoryService.commitReservation(r.reservationId())).isTrue();
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("16");
        assertThat(redis.hasKey(InventoryService.reservationKey(r.reservationId()))).isFalse();
    }

    @Test
    void sweeper_restores_expired_reservations() throws Exception {
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 2L, new BigDecimal("89.99"));
        inventoryService.loadFlashSaleStock(2L);
        Reservation r = inventoryService.reserveFlashSale(1L, 2L, 6);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("14");

        // Force expiry by rewriting the ZSET score into the past and dropping the hash.
        redis.opsForZSet().add(InventoryService.expiryZsetKey(),
                r.reservationId().toString(), System.currentTimeMillis() - 1000);
        redis.delete(InventoryService.reservationKey(r.reservationId()));

        sweeper.sweep();
        // Hash gone path: stock is NOT restored by the sweeper itself (we only restore when the
        // hash is still present and the ZSET says expired). Verify the ZSET entry was cleaned up.
        assertThat(redis.opsForZSet().score(
                InventoryService.expiryZsetKey(), r.reservationId().toString())).isNull();
    }

    @Test
    void reservation_hash_ttl_outlives_business_expiry() {
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 2L, new BigDecimal("89.99"));
        inventoryService.loadFlashSaleStock(2L);
        Reservation r = inventoryService.reserveFlashSale(1L, 2L, 1);

        Long ttlSeconds = redis.getExpire(InventoryService.reservationKey(r.reservationId()));
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isGreaterThanOrEqualTo(reservationTtlSeconds + reservationHashRetentionSeconds - 1);
    }

    @Test
    void sweeper_restores_when_hash_still_present_and_expired() {
        FlashSaleTestData.configureActiveFlashSale(inventoryService, 2L, new BigDecimal("89.99"));
        inventoryService.loadFlashSaleStock(2L);
        Reservation r = inventoryService.reserveFlashSale(1L, 2L, 2);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("18");

        // Force just the ZSET score backwards so sweeper sees it as expired while the hash still exists.
        redis.opsForZSet().add(InventoryService.expiryZsetKey(),
                r.reservationId().toString(), System.currentTimeMillis() - 1000);

        sweeper.sweep();
        assertThat(redis.opsForValue().get(InventoryService.stockKey(2L))).isEqualTo("20");
        assertThat(redis.hasKey(InventoryService.reservationKey(r.reservationId()))).isFalse();
    }

    @Test
    void unknown_reservation_release_is_safe() {
        UUID rando = UUID.randomUUID();
        boolean restored = inventoryService.releaseReservation(rando, 2L, 1);
        assertThat(restored).isFalse();
    }
}
