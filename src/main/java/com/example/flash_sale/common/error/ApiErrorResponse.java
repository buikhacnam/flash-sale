package com.example.flash_sale.common.error;

import java.util.Map;

public record ApiErrorResponse(ApiErrorBody error) {

    public record ApiErrorBody(String code, String message, Map<String, Object> details) {
    }

    public static ApiErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiErrorResponse(new ApiErrorBody(code.name(), message, details));
    }
}
