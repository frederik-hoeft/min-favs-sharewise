package de.hsh.favs.sharewise.shared.infra.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Content requested of a configured issuer, excluding protected JWT metadata.
 *
 * <p>The trusted profile supplies issuer, token type, audience and algorithm.
 * Signing is a cryptographic operation, not a login or authorization workflow.
 *
 * @param subject stable primary IAM user UUID (also used by bound subordinate tokens)
 * @param sessionId opaque primary IAM session-binding ID
 * @param tokenId distinct token ID ({@code jti})
 * @param issuedAt token issuance time
 * @param notBefore validity start
 * @param expiresAt validity end
 * @param principalIssuer primary issuer for subordinate credentials, empty for primary
 * @param assertions additional textual source-owned assertions, excluding reserved JWT claims
 */
public record JwtIssueRequest(
    UUID subject,
    String sessionId,
    String tokenId,
    Instant issuedAt,
    Instant notBefore,
    Instant expiresAt,
    Optional<String> principalIssuer,
    Map<String, String> assertions
) {
    /** Copies assertion data and rejects missing mandatory information. */
    public JwtIssueRequest {
        Objects.requireNonNull(subject, "subject");
        requireText(sessionId, "sessionId");
        requireText(tokenId, "tokenId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(expiresAt, "expiresAt");
        principalIssuer = Objects.requireNonNull(principalIssuer, "principalIssuer");
        principalIssuer.ifPresent(value -> requireText(value, "principalIssuer"));
        assertions = Map.copyOf(Objects.requireNonNull(assertions, "assertions"));
        assertions.forEach((type, value) -> {
            requireText(type, "assertion type");
            Objects.requireNonNull(value, "assertion value");
            if (JwtCredentialClaims.RESERVED_CLAIMS.contains(type)) {
                throw new IllegalArgumentException("Cannot override reserved JWT claim");
            }
        });
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
