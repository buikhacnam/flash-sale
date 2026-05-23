package com.example.flash_sale.payment;

import com.example.flash_sale.common.error.ApiException;
import com.example.flash_sale.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Payment createPending(Long orderId, BigDecimal amount) {
        return paymentRepository.save(new Payment(orderId, amount));
    }

    @Transactional
    public Payment markSuccess(Long orderId) {
        Payment p = require(orderId);
        if (p.getStatus() == PaymentStatus.SUCCESS) {
            return p;
        }
        if (p.getStatus() == PaymentStatus.FAILED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot mark a failed payment as success", Map.of("orderId", orderId));
        }
        p.markSuccess();
        return p;
    }

    @Transactional
    public Payment markFailed(Long orderId) {
        Payment p = require(orderId);
        if (p.getStatus() == PaymentStatus.FAILED) {
            return p;
        }
        if (p.getStatus() == PaymentStatus.SUCCESS) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot mark a successful payment as failed", Map.of("orderId", orderId));
        }
        p.markFailed();
        return p;
    }

    @Transactional(readOnly = true)
    public Payment require(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND,
                        "Payment not found for order", Map.of("orderId", orderId)));
    }
}
