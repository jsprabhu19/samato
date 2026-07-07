package com.samato.orderservice.domain;

/**
 * Saga lifecycle.
 *
 *   RUNNING ──► COMPLETED  (all steps succeeded)
 *   RUNNING ──► COMPENSATING  (a step failed, rolling back)
 *   COMPENSATING ──► FAILED  (compensation failed, manual intervention)
 *
 * Why a separate state machine for the saga when the Order has one too?
 *   - The Order state reflects the business state of the order.
 *   - The Saga state reflects the progress of the orchestration.
 *   - They're related but not identical. For example, an order can be
 *     in PAYED while its saga is still in COMPENSATING (a refund is
 *     being processed).
 */
public enum SagaStatus {
    RUNNING,
    COMPLETED,
    COMPENSATING,
    FAILED
}
