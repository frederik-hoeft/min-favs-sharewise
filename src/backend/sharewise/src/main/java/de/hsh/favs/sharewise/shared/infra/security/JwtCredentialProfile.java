package de.hsh.favs.sharewise.shared.infra.security;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit expectations for one trusted JWT issuer and credential profile.
 *
 * <p>Profiles are selected by trusted application configuration, never by a
 * token's unverified issuer or type. The initial supported JWS algorithm is RS256.
 *
 * @param name application-defined profile identifier retained as claim provenance
 * @param issuer exact trusted JWT issuer
 * @param tokenType exact protected JOSE {@code typ} header
 * @param audiences required audience set, matched exactly
 * @param kind whether the credential authenticates a primary user or is subordinate
 * @param trustedPrimaryIssuers permitted primary issuers for subordinate binding; empty for primary
 * @param maximumLifetime maximum token lifespan between {@code iat} and {@code exp}
 * @param clockSkew tolerated difference from the verifying clock, at most one minute
 */
public record JwtCredentialProfile(
    String name,
    String issuer,
    String tokenType,
    Set<String> audiences,
    Kind kind,
    Set<String> trustedPrimaryIssuers,
    Duration maximumLifetime,
    Duration clockSkew
) {
    /** Trust role of a JWT profile, independent of business authorization. */
    public enum Kind {
        /** Authenticates the principal and its IAM session. */
        PRIMARY,
        /** Adds issuer-qualified evidence requiring a matching primary identity/session. */
        SUBORDINATE
    }

    /** Checks invariants and copies the audience set. */
    public JwtCredentialProfile {
        nonBlank(name, "name");
        nonBlank(issuer, "issuer");
        nonBlank(tokenType, "tokenType");
        audiences = Set.copyOf(Objects.requireNonNull(audiences, "audiences"));
        if (audiences.isEmpty()) {
            throw new IllegalArgumentException("JWT profile requires an audience");
        }
        audiences.forEach(audience -> nonBlank(audience, "audience"));
        Objects.requireNonNull(kind, "kind");
        trustedPrimaryIssuers = Set.copyOf(Objects.requireNonNull(trustedPrimaryIssuers, "trustedPrimaryIssuers"));
        trustedPrimaryIssuers.forEach(issuerId -> nonBlank(issuerId, "trusted primary issuer"));
        if ((kind == Kind.PRIMARY) != trustedPrimaryIssuers.isEmpty()) {
            throw new IllegalArgumentException("Trusted primary issuers must be specified only for subordinate profiles");
        }
        Objects.requireNonNull(maximumLifetime, "maximumLifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        if (maximumLifetime.isNegative() || maximumLifetime.isZero()) {
            throw new IllegalArgumentException("JWT maximum lifetime must be positive");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("JWT clock skew must be between zero and one minute");
        }
    }

    private static void nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
