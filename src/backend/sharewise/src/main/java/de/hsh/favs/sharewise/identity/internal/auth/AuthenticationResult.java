package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Result of a committed successful authentication; HTTP decides token/cookie exposure. */
public record AuthenticationResult(
        UUID userId,
        UUID sessionId,
        IssuedAccessToken accessToken,
        ApplicationTokenGenerator.GeneratedToken refreshCredential,
        Instant refreshExpiresAt) {
    public AuthenticationResult {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshCredential, "refreshCredential");
        Objects.requireNonNull(refreshExpiresAt, "refreshExpiresAt");
    }

    @Override
    public String toString() {
        return "AuthenticationResult[credentials redacted]";
    }
}
