package com.example.flash_sale.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVENTORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    FLASH_SALE_NOT_LOADED(HttpStatus.CONFLICT),
    CART_EMPTY(HttpStatus.BAD_REQUEST),
    RESERVATION_EXPIRED(HttpStatus.CONFLICT),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_NOT_OWNED(HttpStatus.FORBIDDEN),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
