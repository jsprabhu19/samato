package com.samato.paymentservice.eventstore;

import com.samato.paymentservice.domain.event.PaymentEvent;

import java.util.UUID;

/**
 * Thrown when a command expects the aggregate to be at version N,
 * but the latest event for that aggregate is at a different version.
 *
 * Two scenarios:
 *   1. **Stale read**: two commands loaded the aggregate, one appended
 *      first, the second's append is rejected. The second caller
 *      should re-read the aggregate and re-decide.
 *   2. **Concurrent writers**: a different process wrote between our
 *      read and our write.
 *
 * The {@code expectedVersion} and {@code actualVersion} fields let the
 * caller decide what to do (e.g. retry, surface a 409 to the user).
 */
public class OptimisticLockException extends RuntimeException {
    private final UUID aggregateId;
    private final int expectedVersion;
    private final int actualVersion;

    public OptimisticLockException(UUID aggregateId, int expectedVersion, int actualVersion) {
        super("Optimistic lock on " + aggregateId
                + ": expected v=" + expectedVersion + " but found v=" + actualVersion);
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID getAggregateId()    { return aggregateId; }
    public int getExpectedVersion() { return expectedVersion; }
    public int getActualVersion()   { return actualVersion; }
}
