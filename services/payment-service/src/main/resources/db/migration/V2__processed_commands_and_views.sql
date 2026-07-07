-- Payment-service: idempotency table + read model + outbox.
--
-- Idempotency: every command that mutates state carries an idempotency
-- key. Before applying, we INSERT here; a unique violation on
-- (command_type, key) means "we've seen this command before, return the
-- cached result". The cached result_body lets us replay the same HTTP
-- status + payload, so the client gets a deterministic response.
--
-- Why (command_type, key) and not just (key)?
--   A customer could in theory reuse a key across two different
--   commands (e.g. once for a charge, once for a refund). By including
--   the command type, we scope the key correctly.
--
-- Why no customer_id? Commands can come from:
--   - HTTP (the order-service saga)
--   - Kafka (future: a notification-service might trigger a refund)
--   - Admin tools
-- None of these have a customer_id natively; the command itself is
-- the unit of idempotency.

CREATE TABLE processed_commands (
    id              UUID         PRIMARY KEY,
    command_type    VARCHAR(80)  NOT NULL,        -- "CreateRazorpayOrder", "RefundPayment", ...
    key             VARCHAR(200) NOT NULL,        -- the Idempotency-Key header value
    aggregate_id    UUID         NOT NULL,        -- payment id this command created/touched
    result_status   INT          NOT NULL,        -- HTTP-ish: 200/201/202/422
    result_body     TEXT,                         -- serialised response JSON
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_command UNIQUE (command_type, key)
);
CREATE INDEX idx_proc_cmd_aggregate ON processed_commands (aggregate_id);


-- The read model. Materialised by PaymentProjector from the event
-- stream. This is the *only* place "what's the status of this payment?"
-- reads come from — the event store is never read by user-facing
-- queries (it's too slow).
--
-- The view is denormalised:
--   - razorpay_order_id is set when the Razorpay order is created
--   - razorpay_payment_id is set when the payment is captured
--   - status is updated by the projector for every event
--   - last_event_seq is the global sequence number of the last event
--     that touched this row, useful for "is the view caught up?" checks
CREATE TABLE payment_view (
    payment_id            UUID         PRIMARY KEY,
    razorpay_order_id     VARCHAR(40),
    razorpay_payment_id   VARCHAR(40),
    order_id              UUID         NOT NULL,
    customer_id           UUID         NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    last_event_seq        BIGINT       NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_pv_razorpay_order ON payment_view (razorpay_order_id);
CREATE INDEX idx_pv_order          ON payment_view (order_id);
CREATE INDEX idx_pv_customer       ON payment_view (customer_id);


-- Outbox: reliable event publishing to Kafka.
-- Same pattern as order-service. Append in the same transaction as the
-- business write; a poller drains it.
CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID         NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    event_type      VARCHAR(200) NOT NULL,
    payload         BYTEA        NOT NULL,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Partial index: only unsent rows are interesting. Once sent, the row
-- stays but is no longer indexed.
CREATE INDEX idx_outbox_unsent ON outbox_events (sent_at) WHERE sent_at IS NULL;
