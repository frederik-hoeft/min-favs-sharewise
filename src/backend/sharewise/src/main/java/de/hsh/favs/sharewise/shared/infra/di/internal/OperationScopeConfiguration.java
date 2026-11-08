package de.hsh.favs.sharewise.shared.infra.di.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the operation scope before Spring creates application beans. */
@Configuration(proxyBeanMethods = false)
class OperationScopeConfiguration {

    @Bean
    static SpringServiceScopeFactory serviceScopeFactory() {
        return new SpringServiceScopeFactory();
    }
}
