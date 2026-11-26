package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Transfers verified primary evidence to the operation scope; absent evidence is anonymous. */
@Component
final class IamIngressInitializer implements ScopedServiceInitializer<IamOperationSecurityConfiguration, VerifiedJwtCredentialCandidate> {

    @Override
    public Class<IamOperationSecurityConfiguration> configurationType() {
        return IamOperationSecurityConfiguration.class;
    }

    @Override
    public Class<VerifiedJwtCredentialCandidate> inputType() {
        return VerifiedJwtCredentialCandidate.class;
    }

    @Override
    public void initialize(IamOperationSecurityConfiguration configuration, Optional<VerifiedJwtCredentialCandidate> input) {
        configuration.seed(input);
    }
}
