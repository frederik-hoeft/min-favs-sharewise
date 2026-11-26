-- Group-owned bearer invitations. Only the verifier survives issuance.
CREATE DOMAIN groups.natural0 AS integer
    CONSTRAINT groups_natural0_nonnegative CHECK (VALUE >= 0);
CREATE DOMAIN groups.natural1 AS integer
    CONSTRAINT groups_natural1_positive CHECK (VALUE > 0);

REVOKE USAGE ON DOMAIN groups.natural0, groups.natural1 FROM PUBLIC;
GRANT USAGE ON DOMAIN groups.natural0, groups.natural1 TO sharewise_app;

CREATE TABLE groups.invitations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    -- Deletion of a group must explicitly invalidate its invitations in the same transaction.
    group_id uuid NOT NULL REFERENCES groups.groups (id) ON DELETE NO ACTION,
    verifier_hash bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    max_uses groups.natural1,
    use_count groups.natural0 NOT NULL DEFAULT 0,
    CONSTRAINT groups_invitations_verifier_length CHECK (octet_length(verifier_hash) = 32),
    CONSTRAINT groups_invitations_usage_limit CHECK (max_uses IS NULL OR use_count <= max_uses),
    CONSTRAINT groups_invitations_expiration CHECK (expires_at IS NULL OR expires_at > created_at)
);
CREATE INDEX groups_invitations_group_id_idx ON groups.invitations (group_id);

-- Inherit SELECT, INSERT and DELETE from the context's default table grants.
-- Invitation policy and verifier are immutable; redemption changes only the count.
REVOKE UPDATE ON TABLE groups.invitations FROM sharewise_app;
GRANT UPDATE (use_count) ON TABLE groups.invitations TO sharewise_app;
