package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** The single ingress-initialized state holder; no semantic context is initialized directly. */
@Component
@Scope("operation")
final class IamOperationSecurityConfiguration extends OperationScopedConfiguration {

    private Optional<VerifiedJwtCredential> primary;

    void seed(Optional<VerifiedJwtCredential> input) {
        requireInitializing();
        if (primary != null) {
            throw new IllegalStateException("IAM ingress has already been initialized");
        }
        Objects.requireNonNull(input, "input");
        input.ifPresent(credential -> {
            if (credential.kind() != JwtCredentialProfile.Kind.PRIMARY) {
                throw new IllegalArgumentException("IAM ingress requires a verified primary credential");
            }
        });
        primary = input;
    }

    @Override
    protected void validate() {
        if (primary == null) {
            throw new IllegalStateException("IAM ingress initialization did not run");
        }
    }

    Optional<VerifiedJwtCredential> primary() {
        requireReady();
        return primary;
    }
}
