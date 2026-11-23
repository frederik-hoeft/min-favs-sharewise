package de.hsh.favs.sharewise.identity.internal.auth;

import java.util.Objects;
import java.util.UUID;

/** User and session identity originating from the same verified primary IAM bearer. */
public record VerifiedIamSession(UUID userId, UUID sessionId) {
    public VerifiedIamSession {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
