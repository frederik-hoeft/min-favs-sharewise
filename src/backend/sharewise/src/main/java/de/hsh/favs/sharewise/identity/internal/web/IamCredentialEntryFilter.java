package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.shared.infra.web.HttpProblemWriter;
import de.hsh.favs.sharewise.shared.infra.web.AttestationProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationTransport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies no-store and strict same-origin checks at the HTTP boundary before credential entry. */
final class IamCredentialEntryFilter extends OncePerRequestFilter {

    private final HttpProblemWriter problems;

    IamCredentialEntryFilter(HttpProblemWriter problems) {
        this.problems = java.util.Objects.requireNonNull(problems);
    }

    static final String CSRF_PATH = "/api/iam/v1/auth/csrf";
    private static final RequestMatcher CSRF_BOOTSTRAP = PathPatternRequestMatcher.withDefaults().matcher(CSRF_PATH);

    // Cookie-backed authentication operations only. Bearer-only domain and IAM APIs
    // remain outside this CSRF boundary.
    private static final RequestMatcher AUTH_ROUTES = PathPatternRequestMatcher.withDefaults()
            .matcher("/api/iam/v1/auth/**");

    // The password endpoint accepts only a verified IAM bearer, not the refresh cookie.
    // Match both method and exact route so every other unsafe /auth/** URL stays protected.
    private static final RequestMatcher BEARER_PASSWORD_CHANGE = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/iam/v1/auth/password");

    static boolean isAuthMutation(HttpServletRequest request) {
        String method = request.getMethod();
        return !"GET".equals(method) && !"HEAD".equals(method)
                && !"OPTIONS".equals(method) && !"TRACE".equals(method)
                && AUTH_ROUTES.matches(request)
                && !BEARER_PASSWORD_CHANGE.matches(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The bearer-only password command still needs no-store on validation failures,
        // but it must not acquire the cookie-backed Origin/CSRF requirements.
        return !CSRF_BOOTSTRAP.matches(request) && !isAuthMutation(request)
                && !BEARER_PASSWORD_CHANGE.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        // Cookie-backed entry and CSRF bootstrap are primary-only/anonymous paths.
        // In particular bootstrap creates a cookie without dispatching an application operation.
        if ((CSRF_BOOTSTRAP.matches(request) || isAuthMutation(request))
                && request.getHeader(AttestationTransport.HEADER) != null) {
            problems.write(response, AttestationProblems.invalid(OptionalInt.empty()));
            return;
        }
        // A credential mutation must come from the exact effective request origin.
        // Forwarded headers must only be interpreted by a separately trusted proxy configuration.
        if (isAuthMutation(request) && !isSameOrigin(request)) {
            problems.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "invalid_origin", "Origin Verification Failed");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isSameOrigin(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (values.size() != 1) {
            return false;
        }
        try {
            URI origin = new URI(values.getFirst());
            if (origin.getScheme() == null || origin.getHost() == null
                    || origin.getRawUserInfo() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                    || origin.getRawQuery() != null || origin.getRawFragment() != null) {
                return false;
            }
            String scheme = request.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || !scheme.equalsIgnoreCase(origin.getScheme())
                    || !request.getServerName().equalsIgnoreCase(origin.getHost())) {
                return false;
            }
            int port = origin.getPort() == -1
                    ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : origin.getPort();
            return port == request.getServerPort();
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
