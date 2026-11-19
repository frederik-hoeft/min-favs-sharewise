package de.hsh.favs.sharewise.testsupport.identity;

import de.hsh.favs.sharewise.identity.IdentityContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** Explicitly imported test-only wiring; never discovered in production component scans. */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityTestConfiguration {

    @Bean
    @Scope("operation")
    IdentityContext identityContext() {
        Optional<UUID> identity = IdentityTestFixture.currentUserId();
        return () -> identity;
    }
}
