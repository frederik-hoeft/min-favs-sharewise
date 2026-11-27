package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialValidator;
import de.hsh.favs.sharewise.shared.infra.claims.CredentialValidationContext;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtPrincipalBinding;
import de.hsh.favs.sharewise.shared.infra.claims.CredentialValidationResult;
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
                try {
                    JwtPrincipalBinding.requireBound(primary.orElseThrow(JwtCredentialException::new), candidate);
                } catch (JwtCredentialException rejected) {
                    throw new SupplementaryCredentialRejected(candidate);
                }
            }
        }
        CredentialValidationContext context = new CandidateSet(snapshot, primary);
        for (AuthorizationCredentialValidator validator : validators.orderedStream().toList()) {
            CredentialValidationResult result = Objects.requireNonNull(validator.validate(context));
            if (result instanceof CredentialValidationResult.Rejected rejection) {
                VerifiedJwtCredentialCandidate rejected = rejection.candidate().orElse(null);
                if (rejected != null && (rejected.kind() != JwtCredentialProfile.Kind.SUBORDINATE
                        || snapshot.stream().noneMatch(candidate -> candidate == rejected))) {
                    throw new IllegalStateException("Validator rejected a candidate outside its supplementary input set");
                }
                throw new SupplementaryCredentialRejected(rejected);
            }
        }
        return new DefaultAuthorizationContext(snapshot.stream().map(VerifiedJwtCredentialCandidate::evidence).toList());
    }

    private record CandidateSet(List<VerifiedJwtCredentialCandidate> credentials,
                                Optional<VerifiedJwtCredentialCandidate> primary)
        implements CredentialValidationContext { }
}
