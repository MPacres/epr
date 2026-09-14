#!/bin/sh
set -eu

: "${POSTGRES_DB:?POSTGRES_DB must be set}"
: "${POSTGRES_USER:?POSTGRES_USER must be set}"
: "${EPR_DB_MIGRATION_USERNAME:?EPR_DB_MIGRATION_USERNAME must be set}"
: "${EPR_DB_MIGRATION_PASSWORD:?EPR_DB_MIGRATION_PASSWORD must be set}"
: "${EPR_DB_USERNAME:?EPR_DB_USERNAME must be set}"
: "${EPR_DB_PASSWORD:?EPR_DB_PASSWORD must be set}"

if [ "$POSTGRES_USER" = "$EPR_DB_MIGRATION_USERNAME" ] \
    || [ "$POSTGRES_USER" = "$EPR_DB_USERNAME" ] \
    || [ "$EPR_DB_MIGRATION_USERNAME" = "$EPR_DB_USERNAME" ]; then
    echo "Database owner, migration, and runtime role names must be distinct." >&2
    exit 1
fi

psql \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set ON_ERROR_STOP=1 \
    --set migration_username="$EPR_DB_MIGRATION_USERNAME" \
    --set migration_password="$EPR_DB_MIGRATION_PASSWORD" \
    --set runtime_username="$EPR_DB_USERNAME" \
    --set runtime_password="$EPR_DB_PASSWORD" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'migration_username',
    :'migration_password'
) \gexec

SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'runtime_username',
    :'runtime_password'
) \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'migration_username') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_username') \gexec

SELECT format('REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC', current_database()) \gexec
REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE postgres FROM PUBLIC;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'migration_username') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_username') \gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
    :'migration_username',
    :'runtime_username'
) \gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I',
    :'migration_username',
    :'runtime_username'
) \gexec

SELECT format('ALTER ROLE %I SET row_security = on', :'runtime_username') \gexec
SQL
