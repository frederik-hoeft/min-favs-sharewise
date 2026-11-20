package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.ClaimProvider;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimCollection;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimsContext;
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
public class ClaimsConfiguration {

    @Bean
    @Scope("operation")
    ClaimsContext claimsContext(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<ClaimProvider> providers
    ) {
        // Reject incorrectly scoped sources before resolving any of their instances.
        for (String name : beanFactory.getBeanNamesForType(ClaimProvider.class, true, false)) {
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(name);
            if (!"operation".equals(definition.getScope())) {
                throw new IllegalStateException("Claim provider must have operation scope: " + name);
            }
        }

        List<ClaimCollection> verified = new ArrayList<>();
        providers.orderedStream().forEach(provider -> {
            List<ClaimCollection> contributed = Objects.requireNonNull(provider.claims(),
                "Claim provider returned null claim collections");
            for (ClaimCollection collection : contributed) {
                verified.add(Objects.requireNonNull(collection, "Claim provider returned a null claim collection"));
            }
        });
        // No context is published if any provider resolution, verification or copying fails.
        return new DefaultClaimsContext(verified);
    }
}
