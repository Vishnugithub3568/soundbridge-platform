package com.soundbridge.exception;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MigrationException.class)
    public ResponseEntity<Map<String, Object>> handleMigrationException(MigrationException ex) {
        return ResponseEntity
            .status(ex.getStatusCode())
            .body(Map.of(
                "error", ex.getMessage(),
                "errorCode", ex.getErrorCode(),
                "statusCode", ex.getStatusCode(),
                "timestamp", Instant.now()
            ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        int statusCode = ex.getStatusCode().value();
        return ResponseEntity
            .status(statusCode)
            .body(Map.of(
                "error", ex.getReason() != null ? ex.getReason() : "Request failed",
                "errorCode", "HTTP_" + statusCode,
                "statusCode", statusCode,
                "timestamp", Instant.now()
            ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", "Validation failed",
                "errorCode", "VALIDATION_ERROR",
                "statusCode", 400,
                "details", ex.getBindingResult().getAllErrors(),
                "timestamp", Instant.now()
            ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", ex.getMessage(),
                "errorCode", "INVALID_ARGUMENT",
                "statusCode", 400,
                "timestamp", Instant.now()
            ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of(
                "error", "Internal server error",
                "errorCode", "INTERNAL_ERROR",
                "statusCode", 500,
                "message", ex.getMessage(),
                "timestamp", Instant.now()
            ));
    }
}
