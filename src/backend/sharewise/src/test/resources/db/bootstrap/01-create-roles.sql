-- Cluster-wide ShareWise database roles (run once per PostgreSQL server).
-- Dev/test credentials only; other environments provide their own secrets.
--
-- sharewise_migrator: owns the context schemas and runs Flyway migrations (DDL).
-- sharewise_app:      runtime connection of the application; data access only (DML).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharewise_migrator') THEN
        CREATE ROLE sharewise_migrator LOGIN PASSWORD 'sharewise_migrator_dev';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharewise_app') THEN
        CREATE ROLE sharewise_app LOGIN PASSWORD 'sharewise_app_dev';
    END IF;
END
$$;
