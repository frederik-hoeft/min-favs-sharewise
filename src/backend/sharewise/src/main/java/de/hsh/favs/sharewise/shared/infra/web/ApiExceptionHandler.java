package de.hsh.favs.sharewise.shared.infra.web;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends Spring MVC's built-in {@link ResponseEntityExceptionHandler} so standard web exceptions
 * use Spring's native RFC 9457 {@link ProblemDetail} handling while attaching ShareWise's
 * machine-readable {@code code} property and handling both {@link RequestValidationException} and
 * Spring's native validation exceptions.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String DEFAULT_VALIDATION_DETAIL = "Request validation failed.";
    private static final String DEFAULT_FIELD_MESSAGE = "Invalid value";

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ProblemDetail> handleRequestValidation(RequestValidationException exception) {
        ProblemDetail problem = ApiProblems.validationFailed(
                exception.getMessage(),
                exception.fieldErrors());
        return ApiProblems.toResponse(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldErrorDetail> fieldErrors = extractBindingErrors(exception.getBindingResult());
        ProblemDetail problem = ApiProblems.validationFailed(DEFAULT_VALIDATION_DETAIL, fieldErrors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (exception.isForReturnValue()) {
            return super.handleHandlerMethodValidationException(exception, headers, status, request);
        }
        List<FieldErrorDetail> fieldErrors = extractMethodValidationErrors(exception);
        ProblemDetail problem = ApiProblems.validationFailed(DEFAULT_VALIDATION_DETAIL, fieldErrors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
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

    private static List<FieldErrorDetail> extractBindingErrors(Errors errors) {
        List<FieldErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : errors.getFieldErrors()) {
            details.add(new FieldErrorDetail(
                    nonBlankOrDefault(fieldError.getField(), "request"),
                    normalizeValidationCode(fieldError.getCode()),
                    nonBlankOrDefault(fieldError.getDefaultMessage(), DEFAULT_FIELD_MESSAGE)));
        }
        for (ObjectError globalError : errors.getGlobalErrors()) {
            details.add(new FieldErrorDetail(
                    nonBlankOrDefault(globalError.getObjectName(), "request"),
                    normalizeValidationCode(globalError.getCode()),
                    nonBlankOrDefault(globalError.getDefaultMessage(), DEFAULT_FIELD_MESSAGE)));
        }
        return details;
    }

    private static List<FieldErrorDetail> extractMethodValidationErrors(HandlerMethodValidationException exception) {
        List<FieldErrorDetail> details = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                details.addAll(extractBindingErrors(parameterErrors));
            } else {
                String parameterName = resolveParameterName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    if (error instanceof FieldError fieldError) {
                        details.add(new FieldErrorDetail(
                                nonBlankOrDefault(fieldError.getField(), parameterName),
                                normalizeValidationCode(fieldError.getCode()),
                                nonBlankOrDefault(fieldError.getDefaultMessage(), DEFAULT_FIELD_MESSAGE)));
                    } else {
                        details.add(new FieldErrorDetail(
                                parameterName,
                                normalizeValidationCode(extractResolvableCode(error)),
                                nonBlankOrDefault(error.getDefaultMessage(), DEFAULT_FIELD_MESSAGE)));
                    }
                }
            }
        }
        String fallbackTarget = (exception.getMethod() != null)
                ? nonBlankOrDefault(exception.getMethod().getName(), "request")
                : "request";
        for (MessageSourceResolvable error : exception.getCrossParameterValidationResults()) {
            details.add(new FieldErrorDetail(
                    fallbackTarget,
                    normalizeValidationCode(extractResolvableCode(error)),
                    nonBlankOrDefault(error.getDefaultMessage(), DEFAULT_FIELD_MESSAGE)));
        }
        return details;
    }

    private static String resolveParameterName(MethodParameter parameter) {
        if (parameter == null) {
            return "request";
        }
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            if (!requestParam.name().isBlank()) {
                return requestParam.name();
            }
            if (!requestParam.value().isBlank()) {
                return requestParam.value();
            }
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            if (!pathVariable.name().isBlank()) {
                return pathVariable.name();
            }
            if (!pathVariable.value().isBlank()) {
                return pathVariable.value();
            }
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            if (!requestHeader.name().isBlank()) {
                return requestHeader.name();
            }
            if (!requestHeader.value().isBlank()) {
                return requestHeader.value();
            }
        }
        String discoveredName = parameter.getParameterName();
        if (discoveredName != null && !discoveredName.isBlank()) {
            return discoveredName;
        }
        if (parameter.getParameter().isNamePresent()) {
            String reflectionName = parameter.getParameter().getName();
            if (!reflectionName.isBlank()) {
                return reflectionName;
            }
        }
        return "parameter_" + parameter.getParameterIndex();
    }

    private static String extractResolvableCode(MessageSourceResolvable error) {
        if (error instanceof DefaultMessageSourceResolvable defaultResolvable) {
            return defaultResolvable.getCode();
        }
        String[] codes = error.getCodes();
        if (codes != null && codes.length > 0) {
            return codes[codes.length - 1];
        }
        return null;
    }

    static String normalizeValidationCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return "invalid";
        }
        String trimmed = rawCode.trim();
        int dotIndex = trimmed.indexOf('.');
        String base = (dotIndex > 0) ? trimmed.substring(0, dotIndex) : trimmed;
        StringBuilder builder = new StringBuilder(base.length() + 4);
        for (int index = 0; index < base.length(); index++) {
            char current = base.charAt(index);
            if (Character.isUpperCase(current)) {
                if (index > 0
                        && builder.length() > 0
                        && builder.charAt(builder.length() - 1) != '_'
                        && (Character.isLowerCase(base.charAt(index - 1))
                                || (index + 1 < base.length() && Character.isLowerCase(base.charAt(index + 1))))) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(current));
            } else if (Character.isLetterOrDigit(current)) {
                builder.append(current);
            } else if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '_') {
                builder.append('_');
            }
        }
        String normalized = builder.toString();
        return normalized.isBlank() ? "invalid" : normalized;
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
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
