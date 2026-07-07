package com.samato.orderservice.saga;

import com.samato.orderservice.domain.SagaInstance;
import com.samato.orderservice.domain.SagaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The saga poller.
 *
 * The {@link OrderService#placeOrder} path drives the saga synchronously,
 * which is fine when the saga completes in <2 seconds. But what about
 * sagas interrupted by:
 *   - process crash (between steps)
 *   - DB transaction failures
 *   - long-running compensations
 *
 * The poller picks up any saga in RUNNING state and drives it forward.
 * Idempotency: SagaEngine.drive() skips already-COMPLETED steps, so
 * multiple workers (or repeated calls) are safe.
 *
 * Trade-off: latency vs. throughput. Polling every second means
 * worst-case resume latency of 1 second. For Phase 4 that's fine.
 */
@Component
public class SagaPoller {

    private static final Logger log = LoggerFactory.getLogger(SagaPoller.class);

    private final SagaEngine sagaEngine;

    public SagaPoller(SagaEngine sagaEngine) {
        this.sagaEngine = sagaEngine;
    }

    @Scheduled(fixedDelayString = "${samato.saga.poll-ms:1000}")
    public void resumeInProgress() {
        var running = sagaEngine.findInProgress();
        if (running.isEmpty()) return;
        log.debug("Saga poller: {} sagas to resume", running.size());
        for (SagaInstance saga : running) {
            try {
                sagaEngine.drive(saga.getId());
            } catch (Exception e) {
                log.error("Poller: failed to drive saga {}: {}",
                        saga.getId(), e.getMessage());
            }
        }
    }
}
