package com.ideaqr.gateway.web;

import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.exception.UsernameAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates exceptions into consistent, localized JSON envelopes. Validation
 * failures surface per-field Russian messages; duplicate usernames map to 409;
 * everything unexpected becomes a guarded 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Bean-validation failures on @Valid request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ApiResponse body = ApiResponse.error("Проверьте правильность заполнения полей.");
        body.setDetails(fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiResponse> handleDuplicateUsername(UsernameAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Запрос не может быть обработан.";
        if (message.contains("аутентификаци")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(message));
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Внутренняя ошибка сервера. Повторите попытку позже."));
    }
}
