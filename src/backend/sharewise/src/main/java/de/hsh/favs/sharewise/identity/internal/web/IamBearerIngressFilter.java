package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import de.hsh.favs.sharewise.identity.internal.IamCredentialVerifier;
import java.util.Optional;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Verifies primary bearers once before entering an API handler. Ingress evidence
 * is request-local and removed on exit; no application semantic service uses
 * servlet request scope or Spring's thread-local security context.
 *
 * <p>This foundation does not define endpoint authorization policies or login routes.
 */
public final class IamBearerIngressFilter extends OncePerRequestFilter {

    public static final String VERIFIED_INGRESS_ATTRIBUTE = IamBearerIngressFilter.class.getName() + ".verified";
    private static final RequestMatcher HEALTH = PathPatternRequestMatcher.withDefaults().matcher("/api/system/v1/health");
    private final IamCredentialVerifier verifier;

    public IamBearerIngressFilter(IamCredentialVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Use the same decoded route semantics as MVC, including encoded path segments.
        return HEALTH.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Optional<VerifiedJwtCredentialCandidate> identity;
        try {
            // Even anonymous operations require trusted IAM profile configuration.
            verifier.requireCodec();
            List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
            if (headers.isEmpty()) {
                identity = Optional.empty();
            } else if (headers.size() != 1) {
                throw new JwtCredentialException();
            } else {
                String header = headers.getFirst();
                if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    throw new JwtCredentialException();
                }
                String token = header.substring(7);
                if (token.isEmpty() || token.length() > IamCredentialVerifier.MAX_HTTP_ACCESS_TOKEN_LENGTH
                    || token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0 || token.indexOf(',') >= 0) {
                    throw new JwtCredentialException();
                }
                identity = Optional.of(verifier.verify(token));
            }
        } catch (JwtCredentialException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            IamHttpProblemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_credential", "Invalid Bearer Credential");
            return;
        } catch (IllegalStateException exception) {
            IamHttpProblemWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "security_unavailable", "Security Configuration Unavailable");
            return;
        }
        request.setAttribute(VERIFIED_INGRESS_ATTRIBUTE, identity);
        try {
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(VERIFIED_INGRESS_ATTRIBUTE);
        }
    }

}
