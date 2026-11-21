package de.hsh.favs.sharewise.shared.infra.security;

import java.time.Clock;
import java.util.Objects;

/**
 * Issuer/profile-specific JWT credential orchestration, independent of JWS algorithms.
 *
 * <p>The supplied signature service is trusted configuration, never selected
 * from unverified token fields. The codec validates credential semantics after
 * cryptographic verification and before returning evidence to an
 * {@code AuthorizationCredentialProvider}. This does not authorize domain actions
 * or check current account/session persistence.
 *
 * <p>Instances and their signature service/clock dependencies must be safe to share.
 */
public final class JwtCredentialCodec {
    private final JwtCredentialProfile profile;
    private final JwtSignatureService signature;
    private final JwtCredentialClaims claims;

    /**
     * Configures credential validation independently from its trusted JWS implementation.
     *
     * @param profile expected issuer, protected type, audience and credential trust role
     * @param signature independently configured trusted signer/verifier
     * @param clock trusted clock for validating each newly admitted JWT
     */
    public JwtCredentialCodec(JwtCredentialProfile profile, JwtSignatureService signature, Clock clock) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.signature = Objects.requireNonNull(signature, "signature");
        this.claims = new JwtCredentialClaims(profile, Objects.requireNonNull(clock, "clock"));
    }

    /**
     * Returns the trusted credential profile configured for this codec.
     *
     * @return issuer/profile trust policy
     */
    public JwtCredentialProfile profile() {
        return profile;
    }

    /**
     * Signs issuer-controlled claims without making a business authorization decision.
     *
     * @param request typed issuer-controlled claims
     * @return signed compact JWS
     */
    public String sign(JwtIssueRequest request) {
        String token = signature.sign(profile.tokenType(), claims.issue(request));
        if (token.length() > profile.maximumTokenLength()) {
            throw new IllegalArgumentException("JWT exceeds configured profile size limit");
        }
        return token;
    }

    /**
     * Verifies a primary JWT's signature, profile and required IAM identity/session.
     *
     * @param token untrusted compact JWS
     * @return authenticated primary credential proof
     * @throws JwtCredentialException for invalid, untrusted or expired evidence
     */
    public VerifiedJwtCredential verifyPrimary(String token) {
        if (profile.kind() != JwtCredentialProfile.Kind.PRIMARY) {
            throw new JwtCredentialException();
        }
        return verify(token, null);
    }

    /**
     * Verifies subordinate evidence against an accepted issuer-qualified primary
     * credential profile and the current primary principal/session.
     *
     * @param token untrusted subordinate compact JWS
     * @param primary previously admitted primary evidence for principal/session binding
     * @return authenticated and bound subordinate proof, not a domain permission
     * @throws JwtCredentialException for invalid, expired or unbound evidence
     */
    public VerifiedJwtCredential verifySubordinate(String token, VerifiedJwtCredential primary) {
        if (profile.kind() != JwtCredentialProfile.Kind.SUBORDINATE || primary == null
            || primary.kind() != JwtCredentialProfile.Kind.PRIMARY
            || !profile.trustedPrimaryProfiles().contains(new JwtCredentialProfile.Id(
                primary.credential().origin().issuer(), primary.credential().origin().profile()))) {
            throw new JwtCredentialException();
        }
        return verify(token, primary);
    }

    private VerifiedJwtCredential verify(String token, VerifiedJwtCredential primary) {
        try {
            if (token == null || token.length() > profile.maximumTokenLength()) {
                throw new JwtCredentialException();
            }
            return claims.accept(signature.verify(token, profile.tokenType()), primary);
        } catch (Exception exception) {
            // No library exceptions, untrusted content or key material cross this boundary.
            throw new JwtCredentialException();
        }
    }
}
