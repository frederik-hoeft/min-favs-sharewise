package de.hsh.favs.sharewise.shared.infra.claims;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Read-only, infrastructure-facing candidate collection for correlation. */
public interface CredentialValidationContext {
    List<VerifiedJwtCredentialCandidate> credentials();

    Optional<VerifiedJwtCredentialCandidate> primary();

    default List<VerifiedJwtCredentialCandidate> credentialsFrom(JwtCredentialProfile.Id profile) {
        Objects.requireNonNull(profile, "profile");
        return credentials().stream().filter(candidate -> candidate.profile().id().equals(profile)).toList();
    }
}
