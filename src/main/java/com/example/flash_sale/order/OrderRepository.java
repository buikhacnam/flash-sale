package com.example.flash_sale.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByUserIdOrderByIdDesc(Long userId);

    List<Order> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(OrderStatus status, Instant now, Pageable pageable);
}
