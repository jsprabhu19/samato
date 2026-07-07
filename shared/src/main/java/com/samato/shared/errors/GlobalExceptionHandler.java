package com.samato.shared.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standardized error response across all services.
 *
 * Every service includes this (or a copy) so error contracts are uniform
 * for clients and for the API gateway. In an interview, point here when
 * asked "how do you keep error responses consistent across 12 services?"
 *
 * Response shape:
 *   { "timestamp": "...", "status": 404, "code": "RESTAURANT_NOT_FOUND",
 *     "message": "Restaurant 42 does not exist", "traceId": "..." }
 *
 * The `traceId` comes from Micrometer Tracing so the user can quote it
 * and you can find the failing request in Zipkin — production-debugging 101.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException ex) {
        log.warn("Domain exception: code={} status={} msg={}", ex.getCode(), ex.getHttpStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(body(ex.getCode(), ex.getMessage(), ex.getHttpStatus()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("VALIDATION_FAILED", message, 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception ex) {
        // Log full stack — this is a 500, we want the trace in our logs.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "Something went wrong", 500));
    }

    private Map<String, Object> body(String code, String message, int status) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "status", status,
                "code", code,
                "message", message
                // traceId added by a TraceIdFilter (see below)
        );
    }
}
