package de.hsh.favs.sharewise.identity.internal.verification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable temporal projection of a matching, unrevoked session of an enabled account. */
public record SessionValidityWindow(UUID sessionId, Instant createdAt, Instant idleExpiresAt,
        Instant absoluteExpiresAt) {

    public SessionValidityWindow {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(createdAt) && instant.isBefore(idleExpiresAt)
                && instant.isBefore(absoluteExpiresAt);
    }
}
