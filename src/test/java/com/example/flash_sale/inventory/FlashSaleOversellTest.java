package com.example.flash_sale.inventory;

import com.example.flash_sale.TestcontainersConfiguration;
import com.example.flash_sale.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlashSaleOversellTest {

    @Autowired
    InventoryService inventoryService;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void cleanRedis() {
        redis.delete(InventoryService.stockKey(3L));
        redis.delete(InventoryService.expiryZsetKey());
    }

    @Test
    void concurrent_reservations_do_not_oversell() throws Exception {
        int stock = inventoryService.loadFlashSaleStock(3L); // 5
        assertThat(stock).isEqualTo(5);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            Future<?>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                final long userId = i + 1;
                futures[i] = pool.submit(() -> {
                    try {
                        inventoryService.reserveFlashSale(userId, 3L, 1);
                        succeeded.incrementAndGet();
                    } catch (ApiException ex) {
                        failed.incrementAndGet();
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(succeeded.get()).isEqualTo(5);
        assertThat(failed.get()).isEqualTo(15);
        assertThat(redis.opsForValue().get(InventoryService.stockKey(3L))).isEqualTo("0");
    }
}
