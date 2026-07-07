package com.samato.paymentservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the outbox and publishes pending events to Kafka.
 *
 *   - Runs on a fixed delay (configurable via samato.outbox.poll-ms).
 *   - Each batch is read in a separate txn, sent, then marked sent.
 *   - We use {@code .get()} with a timeout for synchronous publish
 *     — we want to know if Kafka is down before we move on.
 *
 * Why "synchronous publish" and not fire-and-forget?
 *   Because in the test environment we want failures to be visible.
 *   In production, you'd want async with a callback that marks
 *   sent_at on success.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public OutboxPublisher(OutboxEventRepository repository,
                           KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findUnsent(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) return;

        for (OutboxEvent e : batch) {
            try {
                kafkaTemplate.send(e.getTopic(), e.getAggregateId().toString(), e.getPayload())
                        .get(5, TimeUnit.SECONDS);
                e.setSentAt(OffsetDateTime.now());
                log.debug("Published outbox event {} to {}", e.getId(), e.getTopic());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Outbox publish interrupted");
                return;
            } catch (ExecutionException | TimeoutException ex) {
                log.warn("Outbox publish failed for {} (will retry): {}", e.getId(), ex.getMessage());
                // Don't mark sent_at; we'll try again next tick.
                return;
            }
        }
    }
}
