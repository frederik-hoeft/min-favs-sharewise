package de.hsh.favs.sharewise.shared.infra.security;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import java.util.Objects;
import java.util.UUID;

/**
 * Proof produced by successful source verification, retaining the primary
 * identity/session binding needed to verify subordinate credentials.
 *
 * <p>Only {@link JwtCredentialCodec} can create this proof. A regular
 * {@link AuthorizationCredential} record is merely evidence data and cannot
 * be substituted for a verification result.
 */
public final class VerifiedJwtCredential {
    private final AuthorizationCredential credential;
    private final UUID userId;
    private final String sessionId;
    private final JwtCredentialProfile.Kind kind;

    VerifiedJwtCredential(AuthorizationCredential credential, UUID userId,
                          String sessionId, JwtCredentialProfile.Kind kind) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /**
     * Returns the immutable source-verified claims and metadata.
     *
     * @return verified application-facing evidence
     */
    public AuthorizationCredential credential() {
        return credential;
    }

    /**
     * Returns the stable primary IAM subject.
     *
     * @return principal user UUID
     */
    public UUID userId() {
        return userId;
    }

    /**
     * Returns the stable primary IAM session-binding identifier.
     *
     * @return primary session ID
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns whether this is primary or subordinate verified evidence.
     *
     * @return configured credential kind
     */
    public JwtCredentialProfile.Kind kind() {
        return kind;
    }
}
