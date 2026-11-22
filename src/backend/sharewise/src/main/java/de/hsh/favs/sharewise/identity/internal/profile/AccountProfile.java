package de.hsh.favs.sharewise.identity.internal.profile;

import java.util.Objects;
import java.util.UUID;

/** The public-facing, credential-free projection of the caller's IAM account. */
public record AccountProfile(UUID userId, String email, String displayName) {
    public AccountProfile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
    }
}
