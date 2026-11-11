package de.hsh.favs.sharewise.shared.infra.web;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Factory methods for building RFC 9457 {@link ProblemDetail} responses with ShareWise's
 * standardized {@code code} and optional {@code fieldErrors} extensions.
 */
public final class ApiProblems {

    public static final String CODE_PROPERTY = "code";
    public static final String FIELD_ERRORS_PROPERTY = "fieldErrors";

    public static final String CODE_VALIDATION_FAILED = "validation_failed";
    public static final String CODE_MALFORMED_REQUEST = "malformed_request";
    public static final String CODE_METHOD_NOT_ALLOWED = "method_not_allowed";
    public static final String CODE_UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type";
    public static final String CODE_NOT_FOUND = "not_found";
    public static final String CODE_INTERNAL_ERROR = "internal_error";

    private static final URI BLANK_TYPE = URI.create("about:blank");

    private ApiProblems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setTitle(title);
        problem.setProperty(CODE_PROPERTY, code);
        return problem;
    }

    public static ProblemDetail validationFailed(String detail, List<FieldErrorDetail> fieldErrors) {
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        ProblemDetail problem = of(
                HttpStatus.BAD_REQUEST,
                CODE_VALIDATION_FAILED,
                "Validation Failed",
                detail);
        problem.setProperty(FIELD_ERRORS_PROPERTY, List.copyOf(fieldErrors));
        return problem;
    }

    public static ResponseEntity<ProblemDetail> toResponse(ProblemDetail problem) {
        Objects.requireNonNull(problem, "problem must not be null");
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    public static ResponseEntity<ProblemDetail> response(
            HttpStatus status,
            String code,
            String title,
            String detail) {
        return toResponse(of(status, code, title, detail));
    }
}
