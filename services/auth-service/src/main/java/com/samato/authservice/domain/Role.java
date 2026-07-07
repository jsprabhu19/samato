package com.samato.authservice.domain;

/**
 * The roles a user can have.
 *
 * In a real system these would be stored in a `roles` table and joined to users.
 * For the bible we keep it as an enum because the role set is small and
 * stable. If your product's roles grow or vary per tenant, switch to a table.
 */
public enum Role {
    CUSTOMER,
    RESTAURANT_OWNER,
    DRIVER,
    ADMIN
}
