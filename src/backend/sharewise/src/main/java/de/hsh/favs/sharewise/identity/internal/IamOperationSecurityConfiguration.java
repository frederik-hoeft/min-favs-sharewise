package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** The single ingress-initialized state holder; no semantic context is initialized directly. */
@Component
@Scope("operation")
final class IamOperationSecurityConfiguration extends OperationScopedConfiguration {

    private final IamCredentialVerifier verifier;
    private Optional<VerifiedJwtCredentialCandidate> primary;

    IamOperationSecurityConfiguration(IamCredentialVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier);
    }

    void seed(Optional<VerifiedJwtCredentialCandidate> input) {
        requireInitializing();
        if (primary != null) {
            throw new IllegalStateException("IAM ingress has already been initialized");
        }
        Objects.requireNonNull(input, "input");
        input.ifPresent(verifier::requireIamPrimary);
        primary = input;
    }

    @Override
    protected void validate() {
        if (primary == null) {
            throw new IllegalStateException("IAM ingress initialization did not run");
        }
    }

    Optional<VerifiedJwtCredentialCandidate> primary() {
        requireReady();
        return primary;
    }
}
