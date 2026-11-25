-- Group Management owns groups, their editable metadata and organizational membership.
-- groups.users is a Group-owned registry of IAM user IDs; it has no FK to any identity table.
-- Invitations (P2.2c) and member management (P2.2b) build on these tables.

CREATE TYPE groups.group_role AS ENUM ('member', 'admin');
REVOKE USAGE ON TYPE groups.group_role FROM PUBLIC;
GRANT USAGE ON TYPE groups.group_role TO sharewise_app;

-- IDs are supplied by fixtures now and by trusted IAM synchronization later; never generated here.
CREATE TABLE groups.users (
    id uuid PRIMARY KEY
);

CREATE TABLE groups.groups (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    name varchar(64) NOT NULL,
    description varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Optimistic concurrency for metadata replacement (P2.2a extension to the ER).
    version bigint NOT NULL DEFAULT 0,
    -- Basic storage sanity only; GroupName/GroupDescription own Unicode validation.
    CONSTRAINT groups_groups_name_normalized CHECK (name <> '' AND name = btrim(name)),
    CONSTRAINT groups_groups_name_no_controls CHECK (name COLLATE "C" !~ '[[:cntrl:]]'),
    CONSTRAINT groups_groups_description_sane CHECK (description IS NULL OR (
        description <> '' AND description = btrim(description, E' \r\n')
        AND replace(replace(description, E'\r\n', E'\n'), E'\n', '') COLLATE "C" !~ '[[:cntrl:]]'
    )),
    CONSTRAINT groups_groups_version_nonnegative CHECK (version >= 0)
);

-- Delete behavior stays NO ACTION until group lifecycle and account deletion are decided.
CREATE TABLE groups.group_members (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    group_id uuid NOT NULL REFERENCES groups.groups (id),
    user_id uuid NOT NULL REFERENCES groups.users (id),
    role groups.group_role NOT NULL,
    joined_at timestamptz NOT NULL DEFAULT now(),
    -- Also serves lookups by group_id, the leading column.
    CONSTRAINT groups_group_members_group_user_unique UNIQUE (group_id, user_id)
);
CREATE INDEX groups_group_members_user_id_idx ON groups.group_members (user_id);
