package de.hsh.favs.sharewise.shared.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationContractTests {

    @Test
    void errorsAreImmutableNonemptyAndDoNotDependOnHttpTypes() {
        ValidationError error = new ValidationError("email", "invalid", "Invalid address");
        List<ValidationError> mutable = new ArrayList<>();
        mutable.add(error);
        ValidationErrors errors = new ValidationErrors(mutable);
        mutable.clear();
        assertEquals(List.of(error), errors.errors());
        assertThrows(UnsupportedOperationException.class, () -> errors.errors().add(error));
        assertThrows(IllegalArgumentException.class, () -> new ValidationErrors(List.of()));
        assertThrows(NullPointerException.class, () -> new ValidationErrors(java.util.Arrays.asList(error, null)));
        assertThrows(IllegalArgumentException.class, () -> new ValidationError("", "invalid", "Invalid"));
        assertThrows(IllegalArgumentException.class, () -> new ValidationError("email", "", "Invalid"));
        assertThrows(IllegalArgumentException.class, () -> new ValidationError("email", "invalid", ""));
    }

    @Test
    void factoryContractExpressesSuccessAndFailureWithoutExceptions() {
        DomainObjectFactory<Integer, String> length = input ->
                input == null ? Result.err(ValidationErrors.single("input", "required", "Required"))
                        : Result.ok(input.length());
        Result<Integer, ValidationErrors> valid = length.createFrom("valid");
        Result<Integer, ValidationErrors> invalid = length.createFrom(null);
        assertTrue(valid.isSuccess());
        assertEquals(Integer.valueOf(5), valid.fold(value -> value, errors -> -1));
        assertFalse(invalid.isSuccess());
        assertEquals("required", invalid.fold(value -> "unexpected", errors -> errors.errors().getFirst().code()));
    }
}
