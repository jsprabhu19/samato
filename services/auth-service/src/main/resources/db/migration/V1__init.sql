-- Auth service schema. Versioned via Flyway.

CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE TABLE user_roles (
    user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(50)  NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE INDEX idx_users_email ON users(email);

CREATE TABLE oauth_clients (
    id                  UUID         PRIMARY KEY,
    client_id           VARCHAR(100) NOT NULL UNIQUE,
    client_secret_hash  VARCHAR(255) NOT NULL,
    redirect_uri        VARCHAR(500),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL
);

CREATE INDEX idx_oauth_clients_client_id ON oauth_clients(client_id);
