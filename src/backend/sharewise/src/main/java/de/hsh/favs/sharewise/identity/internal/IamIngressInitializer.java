package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Bridges verified ingress to the operation scope; missing input is never implicit anonymity. */
@Component
final class IamIngressInitializer implements ScopedServiceInitializer<IamOperationSecurityConfiguration, VerifiedIdentityIngress> {

    @Override
    public Class<IamOperationSecurityConfiguration> configurationType() {
        return IamOperationSecurityConfiguration.class;
    }

    @Override
    public Class<VerifiedIdentityIngress> inputType() {
        return VerifiedIdentityIngress.class;
    }

    @Override
    public void initialize(IamOperationSecurityConfiguration configuration, Optional<VerifiedIdentityIngress> input) {
        configuration.seed(input.orElseThrow(() ->
            new IllegalStateException("An explicit trusted IAM ingress is required")));
    }
}
