-- Database-level grants for the ShareWise roles (run once per database).
-- Uses the current database, so the same script serves the dev database and
-- disposable test databases. Schema and table grants come from Flyway migrations.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO sharewise_migrator', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO sharewise_app', current_database());
    -- PostgreSQL grants TEMPORARY to PUBLIC by default; the application role creates no objects at all.
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
END
$$;
