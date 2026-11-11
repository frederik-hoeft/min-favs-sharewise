package de.hsh.favs.sharewise.shared.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends Spring MVC's built-in {@link ResponseEntityExceptionHandler} so standard web exceptions
 * use Spring's native RFC 9457 {@link ProblemDetail} handling while attaching ShareWise's
 * machine-readable {@code code} property and handling {@link RequestValidationException}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ProblemDetail> handleRequestValidation(RequestValidationException exception) {
        ProblemDetail problem = ApiProblems.validationFailed(
                exception.getMessage(),
                exception.fieldErrors());
        return ApiProblems.toResponse(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        LOG.error("Unhandled exception during API request processing", exception);
        return ApiProblems.response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiProblems.CODE_INTERNAL_ERROR,
                "Internal Server Error",
                "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey(ApiProblems.CODE_PROPERTY)) {
                problem.setProperty(ApiProblems.CODE_PROPERTY, resolveCode(statusCode));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static String resolveCode(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> ApiProblems.CODE_MALFORMED_REQUEST;
            case 404 -> ApiProblems.CODE_NOT_FOUND;
            case 405 -> ApiProblems.CODE_METHOD_NOT_ALLOWED;
            case 415 -> ApiProblems.CODE_UNSUPPORTED_MEDIA_TYPE;
            default -> "http_" + statusCode.value();
        };
    }
}
