package de.hsh.favs.sharewise.shared.infra.web;

import java.util.List;
import java.util.Objects;

/**
 * Thrown at the inbound HTTP boundary when request payload fields or parameters fail validation
 * before dispatching an application operation.
 */
public final class RequestValidationException extends RuntimeException {

    private final List<FieldErrorDetail> fieldErrors;

    public RequestValidationException(List<FieldErrorDetail> fieldErrors) {
        this("Request validation failed.", fieldErrors);
    }

    public RequestValidationException(String detail, List<FieldErrorDetail> fieldErrors) {
        super(Objects.requireNonNull(detail, "detail must not be null"));
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        if (fieldErrors.isEmpty()) {
            throw new IllegalArgumentException("fieldErrors must not be empty");
        }
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static RequestValidationException ofSingleField(String field, String code, String message) {
        return new RequestValidationException(List.of(new FieldErrorDetail(field, code, message)));
    }

    public List<FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }
}
