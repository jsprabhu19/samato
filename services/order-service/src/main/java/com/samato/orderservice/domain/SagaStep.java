package com.samato.orderservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single step within a saga instance.
 *
 * Two important things here:
 *
 *   1. **Idempotency**: each step has a stable {@link #stepType} and
 *      {@link #stepIndex}. When the saga resumes, the engine looks for
 *      a step with the same type+index and continues. Steps that are
 *      COMPLETED are never re-executed.
 *
 *   2. **Compensation data**: a step that succeeded may need to be
 *      undone (a charge has to be refunded). The {@link #payload} field
 *      stores whatever the step needs to compensate (e.g., the
 *      payment-service charge ID for CHARGE_PAYMENT). Without this,
 *      compensation is impossible.
 */
@Entity
@Table(name = "saga_steps", indexes = {
        @Index(name = "idx_step_saga", columnList = "saga_id"),
        @Index(name = "idx_step_status", columnList = "status")
})
public class SagaStep {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "saga_id", nullable = false)
    private SagaInstance saga;

    /** Position in the workflow. 0 = first step. */
    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 50)
    private SagaStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaStepStatus status = SagaStepStatus.PENDING;

    /**
     * Free-form JSON-ish text. Holds whatever the step produced that
     * later steps or compensations need (e.g., paymentId from
     * CHARGE_PAYMENT so REFUND_PAYMENT can call the right endpoint).
     */
    // No @Lob: migration uses TEXT, but Hibernate 6 + Postgres maps @Lob String -> OID.
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public void markRunning() {
        this.status = SagaStepStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markCompleted(String payload) {
        this.status = SagaStepStatus.COMPLETED;
        this.payload = payload;
        this.completedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = SagaStepStatus.FAILED;
        this.errorMessage = error;
    }

    public void markCompensating() { this.status = SagaStepStatus.COMPENSATING; }

    public void markCompensated() {
        this.status = SagaStepStatus.COMPENSATED;
        this.completedAt = Instant.now();
    }

    public void markFailedCompensation(String error) {
        this.status = SagaStepStatus.FAILED_COMPENSATION;
        this.errorMessage = error;
    }

    public UUID getId() { return id; }
    public SagaInstance getSaga() { return saga; }
    public void setSaga(SagaInstance saga) { this.saga = saga; }
    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
    public SagaStepType getStepType() { return stepType; }
    public void setStepType(SagaStepType stepType) { this.stepType = stepType; }
    public SagaStepStatus getStatus() { return status; }
    public void setStatus(SagaStepStatus status) { this.status = status; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
