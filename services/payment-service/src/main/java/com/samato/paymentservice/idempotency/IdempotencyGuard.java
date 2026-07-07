package com.samato.paymentservice.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotency guard.
 *
 *   - Caller wraps their command logic in {@link #executeOnce}.
 *   - The first time (command_type, key) is seen, the supplier runs and
 *     the result is persisted.
 *   - Subsequent calls with the same key replay the persisted result
 *     WITHOUT re-running the supplier. This is what makes the saga's
 *     HTTP retry safe.
 *
 * Why not just rely on the event store's UNIQUE(aggregate_id, version)?
 * Because the saga sends the same CreateRazorpayOrder with the same
 * Idempotency-Key on retry, and we need to know that we already created
 * the Razorpay order (and have the razorpay_order_id from before) —
 * we shouldn't charge the customer twice.
 */
@Component
public class IdempotencyGuard {

    private final ProcessedCommandRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyGuard(ProcessedCommandRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Run {@code work} at most once for the given (command_type, key)
     * pair. If a prior result exists, return it. Otherwise compute,
     * persist, return.
     */
    public <T> T executeOnce(String commandType, String key,
                             UUID aggregateId, Class<T> resultType,
                             Supplier<T> work) {
        Optional<ProcessedCommand> prior =
                repository.findByCommandTypeAndKey(commandType, key);
        if (prior.isPresent()) {
            return deserialize(prior.get().getResultBody(), resultType);
        }
        T result = work.get();
        try {
            recordResult(commandType, key, aggregateId, 200, result);
        } catch (DataIntegrityViolationException race) {
            // Lost a race against another thread that inserted the same
            // (command_type, key) concurrently. Replay the winner.
            ProcessedCommand winner = repository
                    .findByCommandTypeAndKey(commandType, key)
                    .orElseThrow(() -> race);
            return deserialize(winner.getResultBody(), resultType);
        }
        return result;
    }

    /**
     * For webhooks, the dedup key is the razorpay event id (each
     * webhook has a unique id). We just check "have we processed this
     * event id?".
     */
    public Optional<String> findReplay(String commandType, String key) {
        return repository.findByCommandTypeAndKey(commandType, key)
                .map(ProcessedCommand::getResultBody);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(String commandType, String key, UUID aggregateId,
                             int httpStatus, Object result) {
        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise result", e);
        }
        // aggregateId may be null for webhook rows (the dedup is by
        // event id, not aggregate). Use a deterministic UUID derived
        // from the key so we still satisfy the NOT NULL column.
        UUID aid = aggregateId != null ? aggregateId : deriveAggregateId(key);
        repository.save(new ProcessedCommand(
                UUID.randomUUID(), commandType, key, aid, httpStatus, body));
    }

    /**
     * Deterministic UUID for webhook rows whose aggregate id is unknown
     * at dedup time. SHA-1 of the key, type-5 UUID, fits in 36 chars.
     * Storing this is enough for the NOT NULL constraint.
     */
    private static UUID deriveAggregateId(String key) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] uuidBytes = new byte[16];
            System.arraycopy(hash, 0, uuidBytes, 0, 16);
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0F) | 0x50);  // version 5
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3F) | 0x80);  // RFC 4122 variant
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (uuidBytes[i] & 0xFF);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (uuidBytes[i] & 0xFF);
            return new UUID(msb, lsb);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialise prior result", e);
        }
    }
}
