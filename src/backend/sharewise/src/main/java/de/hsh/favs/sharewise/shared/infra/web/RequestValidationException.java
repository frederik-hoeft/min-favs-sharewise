package de.hsh.favs.sharewise.shared.infra.web;

import de.hsh.favs.sharewise.shared.validation.ValidationError;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;

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

    /** Maps transport-neutral validation failures to the existing HTTP error contract. */
    public static RequestValidationException from(ValidationErrors errors) {
        Objects.requireNonNull(errors, "errors");
        List<FieldErrorDetail> fields = errors.errors().stream()
                .map(RequestValidationException::httpField)
                .toList();
        return new RequestValidationException(fields);
    }

    private static FieldErrorDetail httpField(ValidationError error) {
        return new FieldErrorDetail(error.field(), error.code(), error.message());
    }

    public static RequestValidationException ofSingleField(String field, String code, String message) {
        return new RequestValidationException(List.of(new FieldErrorDetail(field, code, message)));
    }

    public List<FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }
}
