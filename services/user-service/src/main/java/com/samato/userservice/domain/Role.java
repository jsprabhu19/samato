package com.samato.userservice.domain;

/**
 * Same enum as in auth-service. We duplicate it (instead of sharing) because:
 *   1. Services own their data; user-service doesn't depend on auth-service.
 *   2. If the role set grows in one and not the other, this won't compile,
 *      which is a feature not a bug — it forces the conversation.
 *
 * In a real system, a `roles` library module shared across services is also fine.
 * The trade-off: a shared module is a coupling; duplicate types are a duplication.
 * For 4 roles, the duplication is cheaper.
 */
public enum Role {
    CUSTOMER,
    RESTAURANT_OWNER,
    DRIVER,
    ADMIN
}
