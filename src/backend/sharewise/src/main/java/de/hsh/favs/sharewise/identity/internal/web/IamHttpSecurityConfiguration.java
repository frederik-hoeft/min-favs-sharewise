package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.shared.infra.web.HttpProblemWriter;
import de.hsh.favs.sharewise.identity.internal.IamCredentialVerifier;
import de.hsh.favs.sharewise.identity.transport.IamApiSecurityContribution;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfException;
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
            CsrfTokenRepository iamCsrfTokenRepository, HttpProblemWriter problems) {
        return new IamApiSecurityContribution() {
            @Override
            public boolean hasVerifiedIdentity(HttpServletRequest request) {
                Object evidence = request.getAttribute(IamBearerIngressFilter.VERIFIED_INGRESS_ATTRIBUTE);
                return evidence instanceof Optional<?> verified && verified.isPresent();
            }

            @Override
            public void contribute(HttpSecurity http) {
                http.csrf(csrf -> csrf.csrfTokenRepository(iamCsrfTokenRepository)
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(IamCredentialEntryFilter::isAuthMutation))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                if (hasVerifiedIdentity(request)) {
                    problems.write(response, HttpServletResponse.SC_FORBIDDEN,
                            "access_denied", "Access Denied");
                } else {
                    response.setHeader(org.springframework.http.HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    problems.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "authentication_required", "IAM Authentication Required");
                }
            }).accessDeniedHandler((request, response, exception) -> {
                boolean csrfFailure = exception instanceof CsrfException;
                problems.write(response, HttpServletResponse.SC_FORBIDDEN,
                        csrfFailure ? "csrf_invalid" : "access_denied",
                        csrfFailure ? "CSRF Validation Failed" : "Access Denied");
            }))
            .addFilterBefore(new IamBearerIngressFilter(verifier, problems), CsrfFilter.class)
            .addFilterAfter(new IamCredentialEntryFilter(problems), IamBearerIngressFilter.class);
            }
        };
    }
}
