package de.hsh.favs.sharewise.shared.validation;

import java.util.List;
import java.util.Objects;

/** Nonempty, immutable collection of input-validation failures. */
public record ValidationErrors(List<ValidationError> errors) {

    public ValidationErrors {
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("Validation errors must not be empty");
        }
    }

    public static ValidationErrors single(String field, String code, String message) {
        return new ValidationErrors(List.of(new ValidationError(field, code, message)));
    }
}
