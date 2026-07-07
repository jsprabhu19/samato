package com.samato.orderservice.domain;

/**
 * The order lifecycle. This is the heart of the saga.
 *
 *   PLACED ─────► VALIDATED ─────► RESERVED ─────► PAID ─────► CONFIRMED
 *      │              │                │             │             │
 *      └──────────────┴────────────────┴─────────────┴─────────────┘
 *                              │ failure at any step
 *                              ▼
 *                          CANCELLED
 *
 * Note: PLACED is the entry state — the order is created. From there the
 * saga drives the transitions. CONFIRMED is terminal-success, CANCELLED
 * is terminal-failure. The state machine is enforced in {@code Order.transitionTo}.
 */
public enum OrderStatus {
    PLACED,
    VALIDATED,
    RESERVED,
    PAID,
    CONFIRMED,
    CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED;
    }
}
