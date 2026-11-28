package de.hsh.favs.sharewise.shared.infra.claims;

import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.Objects;
import java.util.Optional;

/** A domain validator's decision about independently verified evidence, not a business authorization decision. */
public sealed interface CredentialValidationResult {
    record Accepted() implements CredentialValidationResult { }

    /** Empty candidate means the rejection cannot be attributed to one supplied credential. */
    record Rejected(Optional<VerifiedJwtCredentialCandidate> candidate) implements CredentialValidationResult {
        public Rejected {
            Objects.requireNonNull(candidate);
        }
    }

    static CredentialValidationResult accepted() { return new Accepted(); }
    static CredentialValidationResult rejected() { return new Rejected(Optional.empty()); }
    static CredentialValidationResult rejected(VerifiedJwtCredentialCandidate candidate) {
        return new Rejected(Optional.of(candidate));
    }
}
