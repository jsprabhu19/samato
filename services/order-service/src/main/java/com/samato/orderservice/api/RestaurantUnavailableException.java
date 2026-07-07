package com.samato.orderservice.api;

import com.samato.shared.errors.DomainException;

/**
 * Thrown when restaurant-service is unreachable or returning errors.
 * Distinct from "restaurant not found" (which is a domain 404).
 */
public class RestaurantUnavailableException extends DomainException {
    public RestaurantUnavailableException(String message, Throwable cause) {
        super("RESTAURANT_UNAVAILABLE", message, 503, cause);
    }
}
