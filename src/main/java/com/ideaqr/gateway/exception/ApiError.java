package com.ideaqr.gateway.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Uniform error body returned to clients.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiError {

    private int status;
    private String error;
    private String message;
    private LocalDateTime timestamp;
}
