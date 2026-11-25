package de.hsh.favs.sharewise.shared.infra.security.internal;

import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared, thread-safe opaque token primitives; owning modules define token policy. */
@Configuration(proxyBeanMethods = false)
public class ApplicationTokenConfiguration {
    @Bean
    ApplicationTokenGenerator applicationTokenGenerator() {
        return new ApplicationTokenGenerator();
    }

    @Bean
    ApplicationTokenParser applicationTokenParser() {
        return new ApplicationTokenParser();
    }
}
