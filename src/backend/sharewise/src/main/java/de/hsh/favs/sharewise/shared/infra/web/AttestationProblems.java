package de.hsh.favs.sharewise.shared.infra.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** Standard effect-free authorization challenge and supplementary ingress rejection responses. */
public final class AttestationProblems {
    private static final String INVALID_CODE = "invalid_attestation";
    private static final String INVALID_TITLE = "Invalid Attestation";
    private static final String INVALID_DETAIL = "A supplied supplementary credential could not be admitted.";

    private AttestationProblems() { }

    public static ResponseEntity<ProblemDetail> required(AttestationChallenge challenge, String detail) {
        ProblemDetail problem = ApiProblems.of(HttpStatus.UNAUTHORIZED, "attestation_required", "Attestation Required", detail);
        problem.setProperty("authorization", Objects.requireNonNull(challenge));
        return response(problem);
    }

    public static ResponseEntity<ProblemDetail> invalid(OptionalInt rejectedIndex) {
        validateIndex(rejectedIndex);
        ProblemDetail problem = ApiProblems.of(HttpStatus.UNAUTHORIZED, INVALID_CODE, INVALID_TITLE, INVALID_DETAIL);
        rejectedIndex.ifPresent(index -> problem.setProperty("authorization", Map.of("rejectedIndex", index)));
        return response(problem);
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** Writes the same fixed problem before MVC is reached. */
    public static void writeInvalid(HttpServletResponse response, OptionalInt index) throws IOException {
        validateIndex(index);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        // Static trusted strings and a bounded numeric index only; never interpolate token material.
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":401,\"title\":\"" + INVALID_TITLE
            + "\",\"code\":\"" + INVALID_CODE + "\",\"detail\":\"" + INVALID_DETAIL + "\""
            + (index.isPresent() ? ",\"authorization\":{\"rejectedIndex\":" + index.getAsInt() + "}" : "") + "}");
    }

    private static void validateIndex(OptionalInt index) {
        if (index.isPresent() && (index.getAsInt() < 0 || index.getAsInt() >= AttestationTransport.MAX_COUNT)) {
            throw new IllegalArgumentException("Invalid supplementary rejection index");
        }
    }
}
