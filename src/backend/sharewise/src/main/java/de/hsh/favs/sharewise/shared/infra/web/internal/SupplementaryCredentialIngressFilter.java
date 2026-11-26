package de.hsh.favs.sharewise.shared.infra.web.internal;

import de.hsh.favs.sharewise.shared.infra.web.AttestationProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationTransport;

import de.hsh.favs.sharewise.shared.infra.claims.SupplementaryCredentialInputs;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialRouter;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialRejected;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/** Verifies supplementary sources exactly once, before the application operation. */
final class SupplementaryCredentialIngressFilter extends OncePerRequestFilter {
    private static final String INPUT_ATTRIBUTE = SupplementaryCredentialIngressFilter.class.getName() + ".verified";
    private static final Pattern COMPACT_JWS = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private final SupplementaryCredentialRouter router;
    private final Predicate<HttpServletRequest> hasVerifiedPrimary;

    SupplementaryCredentialIngressFilter(SupplementaryCredentialRouter router,
            Predicate<HttpServletRequest> hasVerifiedPrimary) {
        this.router = Objects.requireNonNull(router);
        this.hasVerifiedPrimary = Objects.requireNonNull(hasVerifiedPrimary);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> tokens;
        try {
            tokens = parse(request);
        } catch (JwtCredentialException rejected) {
            AttestationProblems.writeInvalid(response, OptionalInt.empty());
            return;
        }
        if (!tokens.isEmpty() && !hasVerifiedPrimary.test(request)) {
            AttestationProblems.writeInvalid(response, tokens.size() == 1 ? OptionalInt.of(0) : OptionalInt.empty());
            return;
        }
        List<VerifiedJwtCredentialCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            try {
                candidates.add(router.verify(tokens.get(i)));
            } catch (JwtCredentialException rejected) {
                AttestationProblems.writeInvalid(response, OptionalInt.of(i));
                return;
            }
        }
        request.setAttribute(INPUT_ATTRIBUTE, new SupplementaryCredentialInputs(candidates));
        try {
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(INPUT_ATTRIBUTE);
        }
    }

    private static List<String> parse(HttpServletRequest request) {
        List<String> tokens = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int length = 0;
        var fields = request.getHeaders(AttestationTransport.HEADER);
        while (fields.hasMoreElements()) {
            String field = fields.nextElement();
            // Include one separator between normalized field lines.
            int separator = tokens.isEmpty() ? 0 : 1;
            if (field == null || field.length() > AttestationTransport.MAX_HEADER_LENGTH - length - separator) {
                throw new JwtCredentialException();
            }
            length += field.length() + separator;
            for (String element : field.split(",", -1)) {
                String token = trimOws(element);
                if (tokens.size() >= AttestationTransport.MAX_COUNT || token.length() > SupplementaryCredentialRouter.MAX_TOKEN_LENGTH
                        || !COMPACT_JWS.matcher(token).matches() || !seen.add(token)) {
                    throw new JwtCredentialException();
                }
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        return value.substring(start, end);
    }

    /** Read only middleware-created input, rejecting an unprocessed supplied field. */
    static SupplementaryCredentialInputs inputs(HttpServletRequest request) {
        Object input = request.getAttribute(INPUT_ATTRIBUTE);
        if (input instanceof SupplementaryCredentialInputs verified) return verified;
        if (request.getHeader(AttestationTransport.HEADER) != null) {
            throw new IllegalStateException("Trusted supplementary HTTP middleware did not process this request");
        }
        return new SupplementaryCredentialInputs(List.of());
    }

    /** Map a contextual failure to the original normalized header position, if unique. */
    static OptionalInt rejectedIndex(HttpServletRequest request, SupplementaryCredentialRejected rejection) {
        if (!(request.getAttribute(INPUT_ATTRIBUTE) instanceof SupplementaryCredentialInputs input)
                || rejection.candidate().isEmpty()) return OptionalInt.empty();
        var candidates = input.candidates();
        int index = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == rejection.candidate().get()) {
                if (index >= 0) return OptionalInt.empty();
                index = i;
            }
        }
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

}
