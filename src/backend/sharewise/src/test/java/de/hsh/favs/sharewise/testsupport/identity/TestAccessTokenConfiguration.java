package de.hsh.favs.sharewise.testsupport.identity;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Explicitly imported test-only trust for {@link TestAccessTokens}; never discovered in
 * production component scans.
 *
 * <p>It publishes the fixture codec under the name of IAM's primary codec, so the production
 * bearer ingress verifies test-signed tokens and derives {@code IdentityContext} from them.
 * Import it only into contexts that leave IAM authentication disabled, because IAM's own
 * authentication configuration defines that bean when enabled.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAccessTokenConfiguration {

    @Bean("iamPrimaryJwtCredentialCodec")
    JwtCredentialCodec iamPrimaryJwtCredentialCodec() {
        return TestAccessTokens.primaryCodec();
    }
}
