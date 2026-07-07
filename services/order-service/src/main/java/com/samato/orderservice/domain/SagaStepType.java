package com.samato.orderservice.domain;

/**
 * The names of the saga steps. These are the canonical actions the
 * order saga performs. Used as the discriminator on {@link SagaStep}.
 *
 * If you add a step, add it to the WORKFLOW list in {@code SagaEngine}.
 * The compensations list is in REVERSE — when step N fails, steps N-1..0
 * are compensated in that order.
 *
 * The forward path:
 *   VALIDATE_RESTAURANT  → check the restaurant exists and is open
 *   VALIDATE_ITEMS       → verify menu items + prices
 *   RESERVE_INVENTORY    → decrement stock (Phase 4: stub)
 *   CHARGE_PAYMENT       → take money (Phase 4: stub)
 *   CONFIRM_ORDER        → transition order to CONFIRMED + emit event
 */
public enum SagaStepType {
    VALIDATE_RESTAURANT,
    VALIDATE_ITEMS,
    RESERVE_INVENTORY,
    CHARGE_PAYMENT,
    CONFIRM_ORDER
}
