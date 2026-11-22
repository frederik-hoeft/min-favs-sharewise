-- IAM owns account credentials and the initial login/refresh-token history.
-- No cross-context foreign keys or event-delivery tables belong to this slice.
-- Keep the ER's bounded nonnegative counter type in IAM's owning schema.
-- jOOQ generates PostgreSQL domain metadata while exposing its int4 Java value.
CREATE DOMAIN identity.natural0 AS integer
    CONSTRAINT identity_natural0_nonnegative CHECK (VALUE >= 0);
REVOKE USAGE ON DOMAIN identity.natural0 FROM PUBLIC;
GRANT USAGE ON DOMAIN identity.natural0 TO sharewise_app;

CREATE TABLE identity.users (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    email varchar(256) NOT NULL,
    normalized_email varchar(256) NOT NULL,
    password_hash text NOT NULL,
    disabled_at timestamptz,
    failed_login_attempts identity.natural0 NOT NULL DEFAULT 0,
    locked_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_users_normalized_email_unique UNIQUE (normalized_email),
    CONSTRAINT identity_users_password_hash_nonempty CHECK (length(password_hash) > 0)
);

CREATE TABLE identity.sessions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid NOT NULL REFERENCES identity.users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    idle_expires_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT identity_sessions_absolute_after_creation CHECK (absolute_expires_at > created_at),
    CONSTRAINT identity_sessions_idle_after_creation CHECK (idle_expires_at > created_at),
    CONSTRAINT identity_sessions_idle_not_after_absolute CHECK (idle_expires_at <= absolute_expires_at)
);
CREATE INDEX identity_sessions_user_id_idx ON identity.sessions (user_id);

CREATE TABLE identity.refresh_tokens (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    session_id uuid NOT NULL REFERENCES identity.sessions (id),
    verifier_hash bytea NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    consumed_at timestamptz,
    CONSTRAINT identity_refresh_tokens_verifier_length CHECK (octet_length(verifier_hash) = 32),
    CONSTRAINT identity_refresh_tokens_consumed_after_issued
        CHECK (consumed_at IS NULL OR consumed_at >= issued_at)
);
-- Retain consumed generations for reuse detection; no additional session_id index
-- is necessary because the composite B-tree covers that leading column.
CREATE UNIQUE INDEX identity_refresh_tokens_one_active_per_session
    ON identity.refresh_tokens (session_id) WHERE consumed_at IS NULL;
CREATE INDEX identity_refresh_tokens_session_issued_idx
    ON identity.refresh_tokens (session_id, issued_at);
