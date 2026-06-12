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
    private final OrderPersistenceService orderPersistenceService;
    private final int batchSize;

    public OrderExpirySweeper(OrderRepository orderRepository, OrderPersistenceService orderPersistenceService, @Value("${orders.expiry-sweeper.batch-size}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderPersistenceService = orderPersistenceService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${orders.expiry-sweeper.fixed-delay-ms}")
    public void sweep() {
        log.info("OrderExpirySweeper started");
        List<Order> expired = orderRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                OrderStatus.PENDING_PAYMENT,
                Instant.now(),
                PageRequest.of(0, batchSize)
        );

        if (expired.isEmpty()) {
            log.info("No order expired");
            return;
        }

        log.info("Expired order Sweeper found {} expired pending orders", expired.size());

        //loop through the order and expired them one by one
        for (Order order : expired) {
            try {
                orderPersistenceService.expiredOrder(order.getId());
            } catch (RuntimeException ex) {
                log.warn("Failed to expired order with id of {}", order.getId());
            }
        }

    }
}
