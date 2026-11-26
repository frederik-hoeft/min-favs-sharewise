-- One-time initialization of Finance's group roots and actors from Group state (P3.2b, DLV-R15).
-- The publication registry cannot replay Group creations and membership changes committed before
-- the Finance listeners existed, so existing groups and their current members are copied once at
-- deployment time. Ordered after the Group membership revision migration, whose column it reads.
-- This is the only cross-context access between Group and Finance and runs as the migrator before
-- the application accepts requests. The previous backend instance must be stopped before migrating
-- (stop -> migrate -> start), so no Group write occurs between this copy and listener registration;
-- every later membership change then carries a higher revision than the one recorded here.
-- Members who left before the release are not represented; a later rejoin inserts their actor.
-- Idempotent and non-destructive: existing Finance roots and actors are left unchanged.
INSERT INTO finance.groups (id, state)
SELECT id, 'open' FROM groups.groups
ON CONFLICT (id) DO NOTHING;

INSERT INTO finance.actors (group_id, user_id, deleted, upstream_revision)
SELECT m.group_id, m.user_id, false, g.membership_revision
FROM groups.group_members m JOIN groups.groups g ON g.id = m.group_id
ON CONFLICT (group_id, user_id) DO NOTHING;
