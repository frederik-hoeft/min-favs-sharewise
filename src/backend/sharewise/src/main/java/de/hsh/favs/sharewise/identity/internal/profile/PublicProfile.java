package de.hsh.favs.sharewise.identity.internal.profile;

import java.util.Objects;
import java.util.UUID;

/** Authenticated-public projection, intentionally excluding private account fields. */
public record PublicProfile(UUID userId, String displayName) {
    public PublicProfile {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(displayName, "Missing mandatory profile");
    }
}
