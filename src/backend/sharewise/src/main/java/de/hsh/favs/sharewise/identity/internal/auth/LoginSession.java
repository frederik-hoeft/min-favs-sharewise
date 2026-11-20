package de.hsh.favs.sharewise.identity.internal.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable login session identity; the ID is the stable, non-secret JWT sid. */
public record LoginSession(
        UUID id, UUID userId, SessionPolicy.Deadlines deadlines, Instant revokedAt) {

    public LoginSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(deadlines, "deadlines");
    }
}
