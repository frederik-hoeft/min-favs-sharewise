package de.hsh.favs.sharewise.identity.internal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies no-store and strict same-origin checks at the HTTP boundary before credential entry. */
final class IamCredentialEntryFilter extends OncePerRequestFilter {

    static final String CSRF_PATH = "/api/iam/v1/auth/csrf";

    // Cookie-backed authentication operations only. Bearer-only domain and IAM APIs
    // remain outside the CSRF boundary; future refresh/logout use this same path.
    private static final RequestMatcher AUTH_ROUTES = PathPatternRequestMatcher.withDefaults()
            .matcher("/api/iam/v1/auth/**");

    static boolean isAuthMutation(HttpServletRequest request) {
        String method = request.getMethod();
        return !"GET".equals(method) && !"HEAD".equals(method)
                && !"OPTIONS".equals(method) && !"TRACE".equals(method)
                && AUTH_ROUTES.matches(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !CSRF_PATH.equals(path) && !isAuthMutation(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        // A credential mutation must come from the exact effective request origin.
        // Forwarded headers must only be interpreted by a separately trusted proxy configuration.
        if (isAuthMutation(request) && !isSameOrigin(request)) {
            IamHttpProblemWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
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
