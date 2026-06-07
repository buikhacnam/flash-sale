package com.example.flash_sale.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class OrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final int batchSize;

    public OrderExpirySweeper(OrderRepository orderRepository,
                              OrderService orderService,
                              @Value("${orders.expiry-sweeper.batch-size}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${orders.expiry-sweeper.fixed-delay-ms}")
    public void sweep() {
        List<Order> expired = orderRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                OrderStatus.PENDING_PAYMENT,
                Instant.now(),
                PageRequest.of(0, batchSize));
        if (expired.isEmpty()) {
            return;
        }
        log.info("Sweeper found {} expired pending order(s) ready for cleanup", expired.size());
        for (Order order : expired) {
            try {
                orderService.expireOrder(order.getId());
            } catch (RuntimeException ex) {
                log.warn("Failed to expire order {}", order.getId(), ex);
            }
        }
    }
}
