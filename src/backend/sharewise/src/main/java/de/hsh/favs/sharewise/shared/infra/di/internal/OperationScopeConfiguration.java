package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputContributor;
import de.hsh.favs.sharewise.shared.infra.di.ContributingOperationDispatcher;
import java.util.List;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.web.context.annotation.RequestScope;
import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the operation scope before Spring creates application beans. */
@Configuration(proxyBeanMethods = false)
class OperationScopeConfiguration {

    @Bean
    static SpringServiceScopeFactory serviceScopeFactory() {
        return new SpringServiceScopeFactory();
    }

    @Bean
    static OperationDispatcher operationDispatcher(
        SpringServiceScopeFactory serviceScopeFactory,
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<ScopedServiceInitializer<?, ?>> initializers
    ) {
        return new SpringOperationDispatcher(serviceScopeFactory, beanFactory, initializers);
    }

    /** Builds the outer, request-scoped dispatcher using all caller-side contributors. */
    @Bean
    @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
    OperationIngressDispatcher httpOperationIngressDispatcher(
        OperationDispatcher dispatcher, List<OperationInputContributor> contributors
    ) {
        return new ContributingOperationDispatcher(dispatcher, contributors);
    }
}
