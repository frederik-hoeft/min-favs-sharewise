package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialValidator;
import de.hsh.favs.sharewise.shared.infra.claims.CredentialValidationContext;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtPrincipalBinding;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/** Owns the indivisible transition from independent proofs to admitted evidence. */
final class CredentialCorrelation {
    private final ObjectProvider<AuthorizationCredentialValidator> validators;

    CredentialCorrelation(ObjectProvider<AuthorizationCredentialValidator> validators) {
        this.validators = Objects.requireNonNull(validators);
    }

    AuthorizationContext admit(List<VerifiedJwtCredentialCandidate> candidates) {
        List<VerifiedJwtCredentialCandidate> snapshot = List.copyOf(candidates);
        List<VerifiedJwtCredentialCandidate> primaries = snapshot.stream()
            .filter(candidate -> candidate.kind() == JwtCredentialProfile.Kind.PRIMARY).toList();
        if (primaries.size() > 1) {
            throw new JwtCredentialException();
        }
        Optional<VerifiedJwtCredentialCandidate> primary = primaries.stream().findFirst();
        for (VerifiedJwtCredentialCandidate candidate : snapshot) {
            if (candidate.kind() == JwtCredentialProfile.Kind.SUBORDINATE) {
                JwtPrincipalBinding.requireBound(primary.orElseThrow(JwtCredentialException::new), candidate);
            }
        }
        CredentialValidationContext context = new CandidateSet(snapshot, primary);
        validators.orderedStream().forEach(validator -> validator.validate(context));
        return new DefaultAuthorizationContext(snapshot.stream().map(VerifiedJwtCredentialCandidate::evidence).toList());
    }

    private record CandidateSet(List<VerifiedJwtCredentialCandidate> credentials,
                                Optional<VerifiedJwtCredentialCandidate> primary)
        implements CredentialValidationContext { }
}
