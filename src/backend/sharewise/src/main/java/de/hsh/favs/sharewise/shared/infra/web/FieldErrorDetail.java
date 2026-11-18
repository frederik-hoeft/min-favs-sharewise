package de.hsh.favs.sharewise.shared.infra.web;

import java.util.Objects;

/**
 * Describes a single field-level validation failure included in a {@code ProblemDetail} response.
 *
 * @param field the dot-separated field or parameter name
 * @param code a stable machine-readable validation code (e.g. {@code required}, {@code must_be_positive})
 * @param message a human-readable description of the validation error
 */
public record FieldErrorDetail(String field, String code, String message) {

    public FieldErrorDetail {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
