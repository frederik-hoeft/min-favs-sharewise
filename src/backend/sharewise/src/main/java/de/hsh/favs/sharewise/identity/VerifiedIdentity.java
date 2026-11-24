package de.hsh.favs.sharewise.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** IAM user/session pair confirmed eligible against observed state; checkedAt is the temporal evaluation instant. */
public record VerifiedIdentity(UUID userId, UUID sessionId, Instant checkedAt) {

    public VerifiedIdentity {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
