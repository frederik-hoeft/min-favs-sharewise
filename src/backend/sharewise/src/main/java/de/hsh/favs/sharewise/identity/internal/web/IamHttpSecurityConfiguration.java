package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.IamCredentialVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Minimal Spring Security HTTP filter chain for verified primary bearer ingress.
 *
 * <p>URL authorization and login/refresh/cookie flows remain separate tasks. The
 * verifier rejects invalid supplied bearers before MVC dispatch, but protected
 * application operations must still require an authenticated {@code IdentityContext}.
 *
 * <p>The verifier filter is owned only by this chain, never registered a second
 * time as an ordinary servlet filter.
 */
@Configuration(proxyBeanMethods = false)
public class IamHttpSecurityConfiguration {

    @Bean
    FilterChainProxy iamSecurityFilterChain(IamCredentialVerifier verifier) {
        return new FilterChainProxy(new DefaultSecurityFilterChain(
            PathPatternRequestMatcher.pathPattern("/api/**"),
            new SecurityContextHolderFilter(new NullSecurityContextRepository()),
            new IamBearerIngressFilter(verifier)));
    }

}
