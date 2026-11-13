-- Baseline: one schema per bounded context. Business tables are added by the feature slices.
CREATE SCHEMA identity;
CREATE SCHEMA groups;
CREATE SCHEMA finance;
CREATE SCHEMA files;
CREATE SCHEMA notifications;

-- The application role may use the context schemas, but not create objects in them.
GRANT USAGE ON SCHEMA identity, groups, finance, files, notifications TO sharewise_app;

-- Tables and sequences that later migrations create (as the migration role) are
-- automatically readable and writable by the application role.
ALTER DEFAULT PRIVILEGES IN SCHEMA identity, groups, finance, files, notifications
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sharewise_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity, groups, finance, files, notifications
    GRANT USAGE, SELECT ON SEQUENCES TO sharewise_app;
