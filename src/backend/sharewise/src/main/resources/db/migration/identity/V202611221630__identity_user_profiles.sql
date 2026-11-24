-- Profile data is optional and IAM-owned; registration does not create a profile row.
-- The user ID is both the profile's key and its same-context account reference.
CREATE TABLE identity.user_profiles (
    user_id uuid PRIMARY KEY REFERENCES identity.users (id),
    display_name text,
    CONSTRAINT identity_user_profiles_display_name_length
        CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 80),
    CONSTRAINT identity_user_profiles_display_name_content
        CHECK (display_name IS NULL OR (
            display_name ~ '[^[:space:]]'
            AND display_name !~ '[[:cntrl:]]'
            AND display_name !~ U&'[\0080-\009F]'
            AND strpos(display_name, U&'\2028') = 0
            AND strpos(display_name, U&'\2029') = 0
        ))
);
