package com.samato.orderservice.domain;

/**
 * Per-step status. Each saga step is its own little state machine.
 *
 *   PENDING ──► RUNNING ──► COMPLETED
 *                  │           │
 *                  ▼           │
 *               FAILED        │
 *                  │           │
 *                  ▼           ▼
 *             COMPENSATING ──► COMPENSATED
 *                  │
 *                  ▼
 *              FAILED_COMPENSATION
 *
 * Why individual step status?  When the saga is interrupted (process crash,
 * DB hiccup), we can resume from the last PENDING step. We never re-run
 * a COMPLETED step because that would cause double-charges.
 */
public enum SagaStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    FAILED_COMPENSATION
}
