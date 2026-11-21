package de.hsh.favs.sharewise.testsupport.identity;

import de.hsh.favs.sharewise.identity.IdentityContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/**
 * Explicitly imported test-only wiring; never discovered in production component scans.
 *
 * <p>The fixture identity is {@link Primary} so that a test importing this configuration into
 * a full production context (for example a service-level E2E test) binds callers through
 * {@link IdentityTestFixture} instead of the bearer-derived production identity. HTTP tests
 * that exercise the real ingress must not import it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityTestConfiguration {

    @Bean
    @Primary
    @Scope("operation")
    IdentityContext identityContext() {
        Optional<UUID> identity = IdentityTestFixture.currentUserId();
        return () -> identity;
    }
}
