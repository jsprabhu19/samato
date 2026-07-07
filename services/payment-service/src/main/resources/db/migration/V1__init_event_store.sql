-- Payment-service: event store + snapshots.
--
-- Why an event store?
--   In an event-sourced system, the event store is the source of truth.
--   Aggregate state is *reconstructed* by replaying events. We never
--   UPDATE or DELETE rows in the events table — append-only.
--
-- Two ordering concepts intentionally separated:
--   * sequence_number  = GLOBAL, monotonic across all aggregates. Used by
--                        projections to walk the log in strict order.
--   * version          = PER-AGGREGATE. Used for optimistic concurrency.
--                        A command that expects payment v=5 will be rejected
--                        if the payment is currently at v=7.
--
-- The UNIQUE(aggregate_id, version) constraint is the *safety net* for
-- optimistic concurrency. The application code reads the current
-- version, then issues a conditional INSERT; if two writers race, the
-- second one fails with a constraint violation (which we catch and
-- convert to OptimisticLockException).
--
-- event_data is JSONB so we can index individual fields for queries
-- like "all charges for this customer" without a separate column.

CREATE TABLE events (
    sequence_number  BIGSERIAL    PRIMARY KEY,
    aggregate_id     UUID         NOT NULL,
    aggregate_type   VARCHAR(50)  NOT NULL,        -- "Payment"
    event_type       VARCHAR(80)  NOT NULL,        -- "RazorpayOrderCreated", "PaymentCaptured", ...
    event_data       JSONB        NOT NULL,
    version          INT          NOT NULL,        -- per-aggregate version (0,1,2,...)
    command_id       UUID         NOT NULL,        -- for idempotency trace
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A given aggregate can only be at one version at a time.
    -- This is the optimistic-concurrency guarantee.
    CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
);

-- Hot path: "give me the stream for this payment, in order".
CREATE INDEX idx_events_aggregate   ON events (aggregate_id, version);

-- Operational: "show me all PaymentFailed events today" for debugging.
CREATE INDEX idx_events_type        ON events (event_type);

-- Time-ordered: used by projection catch-up.
CREATE INDEX idx_events_created     ON events (created_at);

-- GIN on event_data lets us query "all events where payload->>'orderId' = ?"
-- without a separate column.
CREATE INDEX idx_events_payload_gin ON events USING GIN (event_data jsonb_path_ops);


-- Snapshots: periodic point-in-time captures of an aggregate.
-- Loading a hot wallet: read latest snapshot, replay events after it.
-- Without snapshots, a wallet with 10,000 events would replay all 10,000
-- on every load — fine for the bible demo, painful in production.
CREATE TABLE payment_snapshots (
    payment_id     UUID         PRIMARY KEY,
    version        INT          NOT NULL,
    snapshot_data  JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshots_version ON payment_snapshots (payment_id, version);
