package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
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
}
