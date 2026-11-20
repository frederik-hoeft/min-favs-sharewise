package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import java.util.Optional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** The single ingress-initialized state holder; no semantic context is initialized directly. */
@Component
@Scope("operation")
final class IamOperationSecurityConfiguration extends OperationScopedConfiguration {

    private Optional<VerifiedJwtCredential> primary;

    void seed(VerifiedIdentityIngress input) {
        requireInitializing();
        if (primary != null) {
            throw new IllegalStateException("IAM ingress has already been initialized");
        }
        primary = input.primary();
    }

    @Override
    protected void validate() {
        if (primary == null) {
            throw new IllegalStateException("An explicit trusted IAM ingress is required");
        }
    }

    Optional<VerifiedJwtCredential> primary() {
        requireReady();
        return primary;
    }
}
