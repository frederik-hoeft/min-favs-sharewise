-- Apply with legacy registration/profile writers stopped. The matching application
-- creates a required profile in the account-registration transaction.
-- Preserve existing names and derive defaults solely from stable user IDs.
UPDATE identity.user_profiles
SET display_name = 'User ' || right(user_id::text, 8)
WHERE display_name IS NULL;

INSERT INTO identity.user_profiles (user_id, display_name)
SELECT u.id, 'User ' || right(u.id::text, 8)
FROM identity.users AS u
WHERE NOT EXISTS (
    SELECT 1 FROM identity.user_profiles AS p WHERE p.user_id = u.id
);

ALTER TABLE identity.user_profiles
    ALTER COLUMN display_name SET NOT NULL,
    DROP CONSTRAINT identity_user_profiles_display_name_length,
    DROP CONSTRAINT identity_user_profiles_display_name_content,
    ADD CONSTRAINT identity_user_profiles_display_name_length
        CHECK (char_length(display_name) BETWEEN 1 AND 80),
    ADD CONSTRAINT identity_user_profiles_display_name_content
        CHECK (
            display_name ~ '[^[:space:]]'
            AND display_name !~ '[[:cntrl:]]'
            AND display_name !~ U&'[\0080-\009F]'
            AND strpos(display_name, U&'\2028') = 0
            AND strpos(display_name, U&'\2029') = 0
        );
