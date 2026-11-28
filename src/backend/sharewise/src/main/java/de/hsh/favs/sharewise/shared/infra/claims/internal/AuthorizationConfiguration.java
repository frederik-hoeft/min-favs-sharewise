package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialValidator;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/** Composes only operation-scoped source providers into a single immutable snapshot. */
@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    CredentialCorrelation credentialCorrelation(ObjectProvider<AuthorizationCredentialValidator> validators) {
        return new CredentialCorrelation(validators);
    }

    @Bean
    CredentialAdmission authorizationAdmission(ObjectProvider<AuthorizationContext> authorization) {
        return new CredentialAdmission(authorization);
    }

    @Bean
    @Scope("operation")
    AuthorizationContext authorizationContext(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<AuthorizationCredentialProvider> providers,
        CredentialCorrelation correlation
    ) {
        // Reject incorrectly scoped sources before resolving any of their instances.
        for (String name : beanFactory.getBeanNamesForType(AuthorizationCredentialProvider.class, true, false)) {
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(name);
            if (!"operation".equals(definition.getScope())) {
                throw new IllegalStateException("Authorization credential provider must have operation scope: " + name);
            }
        }

        List<VerifiedJwtCredentialCandidate> candidates = new ArrayList<>();
        providers.orderedStream().forEach(provider -> {
            List<VerifiedJwtCredentialCandidate> contributed = Objects.requireNonNull(provider.credentials(),
                "Authorization credential provider returned null credentials");
            for (VerifiedJwtCredentialCandidate credential : contributed) {
                candidates.add(Objects.requireNonNull(credential, "Authorization credential provider returned a null credential"));
            }
        });
        // No context is published if any provider resolution, verification or copying fails.
        return correlation.admit(candidates);
    }
}
