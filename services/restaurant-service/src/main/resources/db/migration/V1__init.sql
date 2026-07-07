-- Restaurant service schema.

CREATE TABLE restaurants (
    id          UUID         PRIMARY KEY,
    owner_id    UUID         NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    cuisine     VARCHAR(50)  NOT NULL,
    address     VARCHAR(500) NOT NULL,
    city        VARCHAR(100) NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_restaurants_city   ON restaurants(city) WHERE active = TRUE;
CREATE INDEX idx_restaurants_owner  ON restaurants(owner_id);

CREATE TABLE menu_items (
    id            UUID         PRIMARY KEY,
    restaurant_id UUID         NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    name          VARCHAR(200) NOT NULL,
    description   VARCHAR(1000),
    price         NUMERIC(10,2) NOT NULL,
    available     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_menu_items_restaurant ON menu_items(restaurant_id);

-- Outbox table for the transactional outbox pattern.
-- The business write + this row are in the SAME transaction, so they
-- are atomically committed (or both rolled back).
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    event_type     VARCHAR(200) NOT NULL,
    payload        BYTEA        NOT NULL,
    sent_at        TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL
);

-- Partial index: only unsent events are interesting to the poller.
-- Once sent, the row is no longer scanned on every poll.
CREATE INDEX idx_outbox_unsent ON outbox_events(created_at) WHERE sent_at IS NULL;
