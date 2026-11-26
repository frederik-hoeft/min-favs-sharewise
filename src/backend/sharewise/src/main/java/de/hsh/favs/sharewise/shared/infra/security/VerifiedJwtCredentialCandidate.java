package de.hsh.favs.sharewise.shared.infra.security;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Independently verified JWT evidence for the credential admission infrastructure.
 * A subordinate candidate has not yet been correlated to the operation's primary.
 * Only trusted verification code in this package constructs candidates; plain claim
 * data is not a verification proof.
 */
public final class VerifiedJwtCredentialCandidate {
    private final AuthorizationCredential evidence;
    private final JwtCredentialProfile profile;
    private final UUID userId;
    private final String sessionId;
    private final Optional<String> principalIssuer;
    private final Set<String> scalarClaims;

    VerifiedJwtCredentialCandidate(AuthorizationCredential evidence, JwtCredentialProfile profile,
                                   UUID userId, String sessionId, Optional<String> principalIssuer, Set<String> scalarClaims) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.principalIssuer = Objects.requireNonNull(principalIssuer, "principalIssuer");
        this.scalarClaims = Set.copyOf(scalarClaims);
    }

    /** Source-verified evidence, not yet admitted to an application context. */
    public AuthorizationCredential evidence() { return evidence; }

    /** Trusted verification configuration, never supplied by the token. */
    public JwtCredentialProfile profile() { return profile; }

    public UUID userId() { return userId; }

    public String sessionId() { return sessionId; }

    public Optional<String> principalIssuer() { return principalIssuer; }

    /** Distinguishes a verified textual JSON scalar from even a single-element array. */
    public boolean hasScalarClaim(String type) { return scalarClaims.contains(type); }

    public JwtCredentialProfile.Kind kind() { return profile.kind(); }
}
