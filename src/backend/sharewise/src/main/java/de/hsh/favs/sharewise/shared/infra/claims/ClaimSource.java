package de.hsh.favs.sharewise.shared.infra.claims;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable provenance metadata from a source-specific verifier.
 *
 * <p>Constructing this value does <strong>not</strong> establish trust: only a trusted,
 * operation-scoped {@link ClaimProvider} may contribute verified claim sets to
 * {@link ClaimsContext}. The provider must validate issuer, profile, audience,
 * lifetime and any required cross-credential binding before publication.
 */
public record ClaimSource(
    String issuer,
    String profile,
    Set<String> audiences,
    Optional<String> subject,
    Optional<String> credentialId,
    Optional<Instant> issuedAt,
    Optional<Instant> notBefore,
    Optional<Instant> expiresAt
) {

    public ClaimSource {
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
