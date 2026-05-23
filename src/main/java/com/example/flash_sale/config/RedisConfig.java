package com.example.flash_sale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    // Reserves qty atomically against flash-sale stock and records the reservation hash + expiry ZSET.
    // KEYS[1] = flashsale:stock:{productId}
    // KEYS[2] = flashsale:reservation:{reservationId}
    // KEYS[3] = flashsale:reservations:expiry
    // ARGV[1] = qty, ARGV[2] = reservationId, ARGV[3] = userId, ARGV[4] = productId,
    // ARGV[5] = ttlSeconds, ARGV[6] = expiresAtMs
    // Returns 1 on success, 0 if insufficient stock, -1 if stock key missing.
    @Bean
    public RedisScript<Long> reserveStockScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/reserve_stock.lua")));
        s.setResultType(Long.class);
        return s;
    }

    // Restores stock if the reservation hash still exists (cancel path), or if it has expired (sweep path).
    // KEYS[1] = flashsale:stock:{productId}
    // KEYS[2] = flashsale:reservation:{reservationId}
    // KEYS[3] = flashsale:reservations:expiry
    // ARGV[1] = qty, ARGV[2] = reservationId
    // Returns 1 if restored, 0 if reservation hash was missing (already released/confirmed).
    @Bean
    public RedisScript<Long> releaseStockScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/release_stock.lua")));
        s.setResultType(Long.class);
        return s;
    }

    // Commits a reservation on payment confirmation: deletes the reservation hash and ZSET entry
    // WITHOUT returning stock. Stock stays decremented; PG is the post-confirm source of truth.
    // KEYS[1] = flashsale:reservation:{reservationId}
    // KEYS[2] = flashsale:reservations:expiry
    // ARGV[1] = reservationId
    // Returns 1 if committed, 0 if reservation was missing.
    @Bean
    public RedisScript<Long> commitReservationScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/commit_reservation.lua")));
        s.setResultType(Long.class);
        return s;
    }
}
