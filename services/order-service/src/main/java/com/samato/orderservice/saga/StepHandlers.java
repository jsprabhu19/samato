package com.samato.orderservice.saga;

import com.samato.orderservice.domain.SagaInstance;
import com.samato.orderservice.domain.SagaStep;
import com.samato.orderservice.domain.SagaStepStatus;
import org.springframework.stereotype.Component;

/**
 * Placeholder for pluggable step handlers. In Phase 4 the logic lives
 * inside {@link SagaEngine} for clarity.  This class is the seam that
 * future phases (e.g., Phase 5 payment-service) will use to add new
 * step implementations without modifying the engine itself.
 *
 * In other words: the engine's dispatch table is hard-coded; this
 * component is the indirection point we'll fill in Phase 8.
 */
@Component
public class StepHandlers {
    public boolean isCompleted(SagaStep step) {
        return step.getStatus() == SagaStepStatus.COMPLETED;
    }

    public boolean isFailed(SagaStep step) {
        return step.getStatus() == SagaStepStatus.FAILED;
    }

    public String describe(SagaInstance saga) {
        return "saga=" + saga.getId() + " status=" + saga.getStatus()
                + " steps=" + saga.getSteps().size();
    }
}
