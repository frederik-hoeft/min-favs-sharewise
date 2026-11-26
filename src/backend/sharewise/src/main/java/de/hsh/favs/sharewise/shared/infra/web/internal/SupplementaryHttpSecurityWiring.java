package de.hsh.favs.sharewise.shared.infra.web.internal;

import de.hsh.favs.sharewise.shared.infra.claims.internal.SupplementaryCredentialConfiguration;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialRouter;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialVerifier;
import de.hsh.favs.sharewise.shared.infra.web.SupplementaryApiSecurityContribution;
import java.util.List;
import de.hsh.favs.sharewise.shared.infra.web.HttpProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** Composes the filter with its required typed input pipeline; no servlet filter bean is registered. */
@Configuration(proxyBeanMethods = false)
@Import({SupplementaryCredentialConfiguration.class, HttpSupplementaryInputContributor.class})
public class SupplementaryHttpSecurityWiring {
    @Bean
    SupplementaryApiSecurityContribution supplementaryApiSecurityContribution(List<SupplementaryCredentialVerifier> verifiers,
            HttpProblemWriter problems) {
        SupplementaryCredentialRouter router = new SupplementaryCredentialRouter(verifiers);
        return (api, primary) -> api.addFilterBefore(new SupplementaryCredentialIngressFilter(router, primary, problems),
            AuthorizationFilter.class);
    }
}
