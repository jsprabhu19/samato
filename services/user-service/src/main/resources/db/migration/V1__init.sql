-- User service schema. Each profile is a separate table — customers,
-- drivers, restaurant owners have different data shapes.
-- We use the same userId UUID as auth-service; this is a logical reference,
-- not a foreign key (services don't share DBs).

CREATE TABLE customer_profiles (
    user_id          UUID         PRIMARY KEY,
    display_name     VARCHAR(100) NOT NULL,
    phone            VARCHAR(30),
    photo_url        VARCHAR(500),
    preferences_json JSONB,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_customer_profiles_user_id ON customer_profiles(user_id);

CREATE TABLE driver_profiles (
    user_id            UUID         PRIMARY KEY,
    full_name          VARCHAR(100) NOT NULL,
    vehicle_type       VARCHAR(20)  NOT NULL,
    license_plate      VARCHAR(20),
    on_duty            BOOLEAN      NOT NULL DEFAULT FALSE,
    current_latitude   DOUBLE PRECISION,
    current_longitude  DOUBLE PRECISION,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);

CREATE INDEX idx_driver_profiles_on_duty ON driver_profiles(on_duty) WHERE on_duty = TRUE;

CREATE TABLE restaurant_owner_profiles (
    user_id         UUID         PRIMARY KEY,
    business_name   VARCHAR(200) NOT NULL,
    contact_email   VARCHAR(255) NOT NULL,
    contact_phone   VARCHAR(30),
    tax_id          VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
