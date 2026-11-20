package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies primary bearers once before entering an API handler. Ingress evidence
 * is request-local and removed on exit; no application semantic service uses
 * servlet request scope or Spring's thread-local security context.
 *
 * <p>This foundation does not define endpoint authorization policies or login routes.
 */
final class IamBearerIngressFilter extends OncePerRequestFilter {

    static final String VERIFIED_INGRESS_ATTRIBUTE = IamBearerIngressFilter.class.getName() + ".verified";
    private static final int MAX_TOKEN_LENGTH = 8192;
    private final IamOperationIngressService ingress;

    IamBearerIngressFilter(IamOperationIngressService ingress) {
        this.ingress = ingress;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || "/api/system/v1/health".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        VerifiedIdentityIngress identity;
        try {
            // Even anonymous operations require trusted IAM profile configuration.
            ingress.ensureConfigured();
            List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
            if (headers.isEmpty()) {
                identity = VerifiedIdentityIngress.anonymous();
            } else if (headers.size() != 1) {
                throw new JwtCredentialException();
            } else {
                String header = headers.getFirst();
                if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    throw new JwtCredentialException();
                }
                String token = header.substring(7);
                if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH
                    || token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0 || token.indexOf(',') >= 0) {
                    throw new JwtCredentialException();
                }
                identity = ingress.verify(token);
            }
        } catch (JwtCredentialException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problem(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_credential", "Invalid Bearer Credential");
            return;
        } catch (IllegalStateException exception) {
            problem(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
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

    private static void problem(HttpServletResponse response, int status, String code, String title)
        throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status
            + ",\"title\":\"" + title + "\",\"code\":\"" + code + "\"}");
    }
}
