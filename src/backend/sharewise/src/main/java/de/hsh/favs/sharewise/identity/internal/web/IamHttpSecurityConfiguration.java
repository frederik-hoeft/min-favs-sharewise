package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.IamCredentialVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * HTTP transport security for IAM bearer ingress. Verified credentials are passed to the
 * operation scope; Spring's SecurityContext is not the application identity authority.
 *
 * <p>Anonymous credential entry uses Spring CSRF validation and strict same-origin
 * checks. Bearer verification remains the trusted request-to-operation boundary.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class IamHttpSecurityConfiguration {

    @Bean
    CsrfTokenRepository iamCsrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName("__Host-sharewise-csrf");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.path("/").secure(true).httpOnly(true).sameSite("Strict"));
        return repository;
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, IamCredentialVerifier verifier,
            CsrfTokenRepository iamCsrfTokenRepository) throws Exception {
        // All /api requests pass through IAM verification before MVC. Unauthenticated
        // operations enforce their own IdentityContext requirements; the transport
        // must not turn a missing bearer into a Spring Security login redirect.
        return statelessTransport(http)
            .securityMatcher(PathPatternRequestMatcher.pathPattern("/api/**"))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .csrf(csrf -> csrf.csrfTokenRepository(iamCsrfTokenRepository)
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(IamCredentialEntryFilter::isAuthMutation))
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                IamHttpProblemWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "csrf_invalid", "CSRF Validation Failed")))
            .addFilterBefore(new IamBearerIngressFilter(verifier), CsrfFilter.class)
            .addFilterAfter(new IamCredentialEntryFilter(), IamBearerIngressFilter.class)
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain nonApiSecurityFilterChain(HttpSecurity http) throws Exception {
        // Preserve the pre-F0 non-API behavior. Explicit fallback prevents Boot
        // defaults from introducing login or authenticated-by-default routes.
        return statelessTransport(http)
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .build();
    }

    private static HttpSecurity statelessTransport(HttpSecurity http) throws Exception {
        return http
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable());
    }
}
