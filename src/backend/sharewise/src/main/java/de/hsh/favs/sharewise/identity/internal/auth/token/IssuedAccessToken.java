package de.hsh.favs.sharewise.identity.internal.auth.token;

import java.time.Instant;
import java.util.Objects;

/** A sensitive signed access credential and its expiration. */
public record IssuedAccessToken(String value, Instant expiresAt) {
    public IssuedAccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Empty access token");
        }
    }

    @Override
    public String toString() {
        return "IssuedAccessToken[redacted]";
    }
}
