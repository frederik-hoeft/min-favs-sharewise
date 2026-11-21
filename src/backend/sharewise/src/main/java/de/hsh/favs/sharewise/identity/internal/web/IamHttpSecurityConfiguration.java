package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.IamCredentialVerifier;
import de.hsh.favs.sharewise.identity.transport.IamApiSecurityContribution;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * IAM-owned HTTP authentication policy contributed to the application-wide API chain.
 * The host composes the chains; IAM owns primary bearer verification and the
 * cookie-backed credential-entry protections.
 */
@Configuration(proxyBeanMethods = false)
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
    IamApiSecurityContribution iamApiSecurityContribution(IamCredentialVerifier verifier,
            CsrfTokenRepository iamCsrfTokenRepository) {
        return http -> http
            .csrf(csrf -> csrf.csrfTokenRepository(iamCsrfTokenRepository)
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(IamCredentialEntryFilter::isAuthMutation))
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                IamHttpProblemWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "csrf_invalid", "CSRF Validation Failed")))
            .addFilterBefore(new IamBearerIngressFilter(verifier), CsrfFilter.class)
            .addFilterAfter(new IamCredentialEntryFilter(), IamBearerIngressFilter.class);
    }
}
