package de.hsh.favs.sharewise.shared.validation;

import java.util.Objects;

/** A transport-independent failure for one input field or parameter. */
public record ValidationError(String field, String code, String message) {

    public ValidationError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (field.isBlank() || code.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("Validation error fields must not be blank");
        }
    }
}
