package de.hsh.favs.sharewise.shared.infra.security;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit expectations for one trusted JWT issuer and credential profile.
 *
 * <p>Trusted application configuration selects the profile before verification. Its
 * {@code name} is an application-local category, not a JWT header or payload field:
 * the signed token must satisfy the configured issuer, protected type, audience,
 * and validity requirements. The signature algorithm and keys belong to the
 * separately configured signature service.
 *
 * @param name application-defined profile name within the issuer, retained as claim provenance
 * @param issuer exact trusted JWT issuer, limited to the supported mandatory-string length
 * @param tokenType exact protected JOSE {@code typ} header
 * @param audiences required audience set, matched exactly
 * @param kind whether the credential authenticates a primary user or is subordinate
 * @param trustedPrimaryProfiles permitted issuer-qualified primary profiles for subordinate binding; empty for primary
 * @param maximumLifetime maximum token lifespan between {@code iat} and {@code exp}
 * @param clockSkew tolerated difference from the verifying clock, at most one minute
 * @param maximumTokenLength maximum compact JWT length accepted or issued for this profile
 */
public record JwtCredentialProfile(
    String name,
    String issuer,
    String tokenType,
    Set<String> audiences,
    Kind kind,
    Set<Id> trustedPrimaryProfiles,
    Duration maximumLifetime,
    Duration clockSkew,
    int maximumTokenLength
) {
    static final int ABSOLUTE_MAXIMUM_TOKEN_LENGTH = 16_384;

    /**
     * Identifies a configured JWT profile by its issuing authority and local name.
     *
     * <p>The identifier is trusted configuration, not a JWT field. The issuer is
     * verified against {@code iss}; the profile name is attached to accepted
     * evidence only after the token satisfies that profile's validation rules.
     *
     * @param issuer exact trusted issuer, limited to the supported mandatory-string length
     * @param name application-defined credential category within that issuer
     */
    public record Id(String issuer, String name) {
        /** Validates the qualified profile identifier. */
        public Id {
            requireIssuer(issuer);
            nonBlank(name, "name");
        }
    }

    /** Trust role of a JWT profile, independent of business authorization. */
    public enum Kind {
        /** Authenticates the principal and its IAM session. */
        PRIMARY,
        /** Adds issuer-qualified evidence requiring a matching primary identity/session. */
        SUBORDINATE
    }

    /**
     * Returns this profile's issuer-qualified identifier.
     *
     * @return locally configured issuer and profile name
     */
    public Id id() {
        return new Id(issuer, name);
    }

    /** Checks invariants and copies the audience and trusted-primary sets. */
    public JwtCredentialProfile {
        nonBlank(name, "name");
        requireIssuer(issuer);
        nonBlank(tokenType, "tokenType");
        audiences = Set.copyOf(Objects.requireNonNull(audiences, "audiences"));
        if (audiences.isEmpty()) {
            throw new IllegalArgumentException("JWT profile requires an audience");
        }
        audiences.forEach(audience -> nonBlank(audience, "audience"));
        Objects.requireNonNull(kind, "kind");
        trustedPrimaryProfiles = Set.copyOf(Objects.requireNonNull(trustedPrimaryProfiles, "trustedPrimaryProfiles"));
        if ((kind == Kind.PRIMARY) != trustedPrimaryProfiles.isEmpty()) {
            throw new IllegalArgumentException("Trusted primary profiles must be specified only for subordinate profiles");
        }
        Objects.requireNonNull(maximumLifetime, "maximumLifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        if (maximumLifetime.isNegative() || maximumLifetime.isZero()) {
            throw new IllegalArgumentException("JWT maximum lifetime must be positive");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("JWT clock skew must be between zero and one minute");
        }
        if (maximumTokenLength < 1 || maximumTokenLength > ABSOLUTE_MAXIMUM_TOKEN_LENGTH) {
            throw new IllegalArgumentException("JWT profile size limit is outside supported bounds");
        }
    }

    private static void requireIssuer(String issuer) {
        Objects.requireNonNull(issuer, "issuer");
        if (!JwtCredentialClaims.isValidRequiredString(issuer)) {
            throw new IllegalArgumentException("issuer must be nonblank and at most 256 characters long");
        }
    }

    private static void nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
