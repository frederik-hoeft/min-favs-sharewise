package de.hsh.favs.sharewise.identity.internal.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable IAM account state read by registration/login transactions. */
public record AccountSnapshot(
        UUID userId,
        EmailAddress email,
        String encodedPassword,
        Instant createdAt,
        Instant disabledAt,
        LoginLockoutPolicy.State loginFailures) {

    public AccountSnapshot {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(encodedPassword, "encodedPassword");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(loginFailures, "loginFailures");
        if (encodedPassword.isBlank()) {
            throw new IllegalArgumentException("Encoded password must not be empty");
        }
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    @Override
    public String toString() {
        return "AccountSnapshot[userId=" + userId + ", sensitive fields redacted]";
    }
}
