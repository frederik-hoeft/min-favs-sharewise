-- Finance owns its lifecycle and revision types independently of Group Management.
CREATE TYPE finance.group_state AS ENUM ('provisioning', 'open', 'closed', 'archived');

CREATE DOMAIN finance.bignatural0 AS bigint
    CONSTRAINT finance_bignatural0_nonnegative CHECK (VALUE >= 0);
CREATE DOMAIN finance.bignatural1 AS bigint
    CONSTRAINT finance_bignatural1_positive CHECK (VALUE > 0);

REVOKE USAGE ON TYPE finance.group_state FROM PUBLIC;
GRANT USAGE ON TYPE finance.group_state TO sharewise_app;
REVOKE USAGE ON DOMAIN finance.bignatural0, finance.bignatural1 FROM PUBLIC;
GRANT USAGE ON DOMAIN finance.bignatural0, finance.bignatural1 TO sharewise_app;

-- IDs are upstream identities, not cross-context foreign keys.
CREATE TABLE finance.groups (
    id uuid PRIMARY KEY,
    state finance.group_state NOT NULL DEFAULT 'provisioning',
    current_revision finance.bignatural0 NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE finance.actors (
    group_id uuid NOT NULL REFERENCES finance.groups (id),
    user_id uuid NOT NULL,
    deleted boolean NOT NULL,
    upstream_revision finance.bignatural1 NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);

-- Diagnostic transaction-start timestamp; source revision determines event ordering.
CREATE FUNCTION finance.set_actor_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION finance.set_actor_updated_at() FROM PUBLIC;

CREATE TRIGGER actors_set_updated_at
    BEFORE UPDATE ON finance.actors
    FOR EACH ROW EXECUTE FUNCTION finance.set_actor_updated_at();
