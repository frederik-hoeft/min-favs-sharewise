package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
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
    @Scope("operation")
    AuthorizationContext authorizationContext(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<AuthorizationCredentialProvider> providers
    ) {
        // Reject incorrectly scoped sources before resolving any of their instances.
        for (String name : beanFactory.getBeanNamesForType(AuthorizationCredentialProvider.class, true, false)) {
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(name);
            if (!"operation".equals(definition.getScope())) {
                throw new IllegalStateException("Authorization credential provider must have operation scope: " + name);
            }
        }

        List<AuthorizationCredential> verified = new ArrayList<>();
        providers.orderedStream().forEach(provider -> {
            List<AuthorizationCredential> contributed = Objects.requireNonNull(provider.credentials(),
                "Authorization credential provider returned null credentials");
            for (AuthorizationCredential credential : contributed) {
                verified.add(Objects.requireNonNull(credential, "Authorization credential provider returned a null credential"));
            }
        });
        // No context is published if any provider resolution, verification or copying fails.
        return new DefaultAuthorizationContext(verified);
    }
}
