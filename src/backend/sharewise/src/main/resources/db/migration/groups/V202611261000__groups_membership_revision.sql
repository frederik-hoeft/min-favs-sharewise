-- Group-local membership source revision, published with every membership fact (P3.2b).
-- The membership write adapters advance it by exactly one with each group_members insert or
-- delete under the group row lock; no other path changes it. Role changes do not advance it.
-- A new group starts at 1, the revision of its initial owner membership; existing rows receive 1.

-- Defined independently of finance.bignatural1: contexts do not share database types.
CREATE DOMAIN groups.bignatural1 AS bigint
    CONSTRAINT groups_bignatural1_positive CHECK (VALUE > 0);

REVOKE USAGE ON DOMAIN groups.bignatural1 FROM PUBLIC;
GRANT USAGE ON DOMAIN groups.bignatural1 TO sharewise_app;

ALTER TABLE groups.groups
    ADD COLUMN membership_revision groups.bignatural1 NOT NULL DEFAULT 1;
