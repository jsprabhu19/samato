package com.samato.orderservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The saga instance — a single orchestration run for a single order.
 *
 * Why persist the saga?
 *   1. **Durability**: if the order-service crashes mid-saga, we resume
 *      from the last PENDING step on restart. Sagas are not in memory.
 *   2. **Auditability**: a row per saga gives us a complete history of
 *      every order attempt. We can see how long compensations took,
 *      which steps failed most, etc.
 *   3. **Idempotency**: the sagaId is the unique key — re-running the
 *      engine for the same saga picks up where it left off.
 *
 * The {@link #steps} are an owned collection (cascade = ALL) — the saga
 * and its steps are loaded and written as one aggregate. We never load
 * a step without its saga.
 */
@Entity
@Table(name = "saga_instances", indexes = {
        @Index(name = "idx_saga_order", columnList = "order_id"),
        @Index(name = "idx_saga_status", columnList = "status")
})
public class SagaInstance {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaStatus status = SagaStatus.RUNNING;

    @Column(name = "current_step_index", nullable = false)
    private int currentStepIndex = 0;

    @Column(length = 1000)
    private String failureReason;

    @OneToMany(mappedBy = "saga", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("stepIndex ASC")
    private List<SagaStep> steps = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void addStep(SagaStep step) {
        step.setSaga(this);
        this.steps.add(step);
    }

    /**
     * Returns the next step to run, or null if all steps are done.
     * "Done" means COMPLETED. FAILED and PENDING both need attention.
     */
    public SagaStep nextPendingStep() {
        return steps.stream()
                .filter(s -> s.getStatus() == SagaStepStatus.PENDING)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the most recent COMPLETED step that hasn't been compensated.
     * Used during compensation to walk backwards.
     */
    public SagaStep lastCompletedStep() {
        for (int i = steps.size() - 1; i >= 0; i--) {
            SagaStep s = steps.get(i);
            if (s.getStatus() == SagaStepStatus.COMPLETED) return s;
        }
        return null;
    }

    public boolean isFinished() {
        return status == SagaStatus.COMPLETED
                || status == SagaStatus.FAILED
                || (status == SagaStatus.COMPENSATING
                    && steps.stream().allMatch(s ->
                        s.getStatus() == SagaStepStatus.COMPENSATED
                        || s.getStatus() == SagaStepStatus.FAILED_COMPENSATION));
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public SagaStatus getStatus() { return status; }
    public void setStatus(SagaStatus status) { this.status = status; }
    public int getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(int currentStepIndex) { this.currentStepIndex = currentStepIndex; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public List<SagaStep> getSteps() { return steps; }
    public void setSteps(List<SagaStep> steps) { this.steps = steps; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
