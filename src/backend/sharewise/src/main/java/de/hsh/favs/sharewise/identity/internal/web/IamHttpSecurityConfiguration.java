package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.IamCredentialVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * HTTP transport security for IAM bearer ingress. Verified credentials are passed to the
 * operation scope; Spring's SecurityContext is not the application identity authority.
 *
 * <p>Credential entry and CSRF are added in Phase F1. Until then, there are no live
 * state-changing IAM endpoints, so the legacy API transport behavior is preserved.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class IamHttpSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, IamCredentialVerifier verifier) throws Exception {
        // All /api requests pass through IAM verification before MVC. Unauthenticated
        // operations enforce their own IdentityContext requirements; the transport
        // must not turn a missing bearer into a Spring Security login redirect.
        return statelessTransport(http)
            .securityMatcher(PathPatternRequestMatcher.pathPattern("/api/**"))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .addFilterBefore(new IamBearerIngressFilter(verifier), AuthorizationFilter.class)
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain nonApiSecurityFilterChain(HttpSecurity http) throws Exception {
        // Preserve the pre-F0 non-API behavior. Explicit fallback prevents Boot
        // defaults from introducing login or authenticated-by-default routes.
        return statelessTransport(http)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .build();
    }

    private static HttpSecurity statelessTransport(HttpSecurity http) throws Exception {
        // F1 must explicitly configure CSRF and Origin validation before exposing
        // registration/login; no credential-changing HTTP endpoints exist in F0.
        return http
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable());
    }
}
