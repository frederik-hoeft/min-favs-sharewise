package de.hsh.favs.sharewise.identity.internal.auth;

import java.time.Instant;
import java.util.UUID;

/** IAM-specific issuance intent; production delegates signing to P2.1.0 shared JOSE. */
public interface AccessTokenIssuer {
    IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant expiresAt);
}
