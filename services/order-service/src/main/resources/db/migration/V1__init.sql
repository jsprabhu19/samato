-- Order-service initial schema.
--
-- All tables are created with UUID PKs. We use `uuid` (not `uuid-ossp`)
-- to keep the schema simple; Hibernate generates the values.

-- Orders
CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    customer_id         UUID NOT NULL,
    restaurant_id       UUID NOT NULL,
    status              VARCHAR(20) NOT NULL,
    total_amount        NUMERIC(10,2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    cancellation_reason VARCHAR(500),
    saga_id             UUID,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_orders_customer   ON orders (customer_id);
CREATE INDEX idx_orders_restaurant ON orders (restaurant_id);
CREATE INDEX idx_orders_status     ON orders (status);

-- Order items
CREATE TABLE order_items (
    id            UUID PRIMARY KEY,
    order_id      UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    menu_item_id  UUID NOT NULL,
    name          VARCHAR(200) NOT NULL,
    quantity      INT NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL,
    created_at    TIMESTAMP NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

-- Saga instances
CREATE TABLE saga_instances (
    id                   UUID PRIMARY KEY,
    order_id             UUID NOT NULL,
    status               VARCHAR(20) NOT NULL,
    current_step_index   INT NOT NULL DEFAULT 0,
    failure_reason       VARCHAR(1000),
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    version              BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_saga_order  ON saga_instances (order_id);
CREATE INDEX idx_saga_status ON saga_instances (status);

-- Saga steps
CREATE TABLE saga_steps (
    id            UUID PRIMARY KEY,
    saga_id       UUID NOT NULL REFERENCES saga_instances(id) ON DELETE CASCADE,
    step_index    INT NOT NULL,
    step_type     VARCHAR(50) NOT NULL,
    status        VARCHAR(30) NOT NULL,
    payload       TEXT,
    error_message VARCHAR(1000),
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    created_at    TIMESTAMP NOT NULL
);
CREATE INDEX idx_step_saga   ON saga_steps (saga_id);
CREATE INDEX idx_step_status ON saga_steps (status);

-- Idempotency records
CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL,
    customer_id     UUID NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body   TEXT,
    order_id        UUID,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_idem UNIQUE (idempotency_key)
);
CREATE INDEX idx_idem_key ON idempotency_records (idempotency_key);

-- Outbox events
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    event_type      VARCHAR(200) NOT NULL,
    payload         BYTEA NOT NULL,
    sent_at         TIMESTAMP,
    created_at      TIMESTAMP NOT NULL
);
CREATE INDEX idx_outbox_unsent ON outbox_events (sent_at);
