package de.hsh.favs.sharewise.shared.infra.claims;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable provenance metadata associated with one authorization credential.
 *
 * <p>Instantiation alone does not establish trust. Only source verification followed by contextual admission
 * can publish credentials into an operation's {@link AuthorizationContext}.
 *
 * @param issuer trusted issuer identifier
 * @param profile verified credential profile (such as an IAM access token)
 * @param audiences verified intended audiences
 * @param subject optional subject identity
 * @param credentialId optional credential identifier
 * @param issuedAt optional issuance time
 * @param notBefore optional start of validity
 * @param expiresAt optional end of validity
 */
public record ClaimOrigin(
    String issuer,
    String profile,
    Set<String> audiences,
    Optional<String> subject,
    Optional<String> credentialId,
    Optional<Instant> issuedAt,
    Optional<Instant> notBefore,
    Optional<Instant> expiresAt
) {

    /** Validates required metadata and defensively copies audience identifiers. */
    public ClaimOrigin {
        requireNonBlank(issuer, "issuer");
        requireNonBlank(profile, "profile");
        Objects.requireNonNull(audiences, "audiences");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String audience : audiences) {
            requireNonBlank(audience, "audience");
            copy.add(audience);
        }
        audiences = Collections.unmodifiableSet(copy);
        subject = Objects.requireNonNull(subject, "subject");
        credentialId = Objects.requireNonNull(credentialId, "credentialId");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        notBefore = Objects.requireNonNull(notBefore, "notBefore");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        subject.ifPresent(value -> requireNonBlank(value, "subject"));
        credentialId.ifPresent(value -> requireNonBlank(value, "credentialId"));
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
