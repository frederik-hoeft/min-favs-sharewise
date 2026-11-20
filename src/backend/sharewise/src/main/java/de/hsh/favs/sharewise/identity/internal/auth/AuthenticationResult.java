package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;

import java.util.Objects;
import java.util.UUID;

/** Result of a committed successful authentication; HTTP decides token/cookie exposure. */
public record AuthenticationResult(
        UUID userId,
        UUID sessionId,
        IssuedAccessToken accessToken,
        RefreshCredentialGenerator.GeneratedCredential refreshCredential) {
    public AuthenticationResult {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshCredential, "refreshCredential");
    }

    @Override
    public String toString() {
        return "AuthenticationResult[credentials redacted]";
    }
}
