package com.ideaqr.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/** Generic success/error envelope for simple endpoints. */
@Data
@Builder
@AllArgsConstructor
public class ApiResponse {

    private boolean success;
    private String message;
    private Map<String, Object> details;

    public static ApiResponse ok(String message) {
        return ApiResponse.builder().success(true).message(message).build();
    }

    public static ApiResponse error(String message) {
        return ApiResponse.builder().success(false).message(message).build();
    }

    public ApiResponse with(String key, Object value) {
        if (details == null) {
            details = new HashMap<>();
        }
        details.put(key, value);
        return this;
    }
}
