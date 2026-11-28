-- One-time initialization of Group's local user projection (P2.4a, D10).
-- The publication registry cannot replay registrations committed before the Group listener
-- existed, so existing IAM accounts are copied once at deployment time. This is the only
-- cross-schema statement and runs as the migrator before the application accepts requests;
-- the previous backend instance must be stopped before migrating (stop -> migrate -> start).
-- At runtime, Group never reads IAM tables. Idempotent: known IDs are left unchanged.
INSERT INTO groups.users (id)
SELECT id FROM identity.users
ON CONFLICT (id) DO NOTHING;
