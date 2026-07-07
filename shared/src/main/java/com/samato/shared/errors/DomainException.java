package com.samato.shared.errors;

/**
 * Base exception for any business-rule violation a service wants to surface
 * to the client as a 4xx (instead of leaking a 500).
 *
 * Each service can subclass this for its own domain exceptions
 * (e.g. RestaurantNotFoundException, OrderAlreadyCancelledException).
 *
 * Why a shared base? Centralized error → JSON mapping in the gateway
 * and uniform logging. See GlobalExceptionHandler in each service.
 */
public class DomainException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public DomainException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public DomainException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
}
