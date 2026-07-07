#!/bin/bash
# Creates one database per service so services can be wired up independently.
# Runs ONCE on first Postgres startup.
set -e

create_db() {
  local db=$1
  echo "Creating database: $db"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
    GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
}

create_db config
create_db discovery
create_db auth
create_db user_service
create_db restaurant_service
create_db order_service
create_db payment_service
create_db delivery_service
create_db notification_service
create_db search_service
create_db analytics_service
create_db zipkin

echo "All service databases created."
