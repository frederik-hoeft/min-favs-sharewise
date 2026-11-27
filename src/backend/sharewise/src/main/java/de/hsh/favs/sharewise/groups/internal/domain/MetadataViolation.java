package de.hsh.favs.sharewise.groups.internal.domain;

import de.hsh.favs.sharewise.shared.validation.UnicodeTextValidator;
import java.util.Objects;

/**
 * One rejected group metadata field and the rule it violates.
 *
 * @param field the rejected metadata field
 * @param reason the violated rule
 */
public record MetadataViolation(Field field, Reason reason) {

    public MetadataViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(reason, "reason");
    }

    /** Editable group metadata fields. */
    public enum Field {
        NAME,
        DESCRIPTION
    }

    /** Validation rules for group metadata. */
    public enum Reason {
        /** The value is missing or blank after trimming. */
        REQUIRED,
        /** The trimmed value exceeds the maximum number of Unicode code points. */
        TOO_LONG,
        /** The value contains a control character that the field does not allow. */
        CONTROL_CHARACTER,
        /** The value falls outside the shared free-text policy or lacks meaningful name content. */
        UNSUPPORTED_CHARACTER;

        static Reason fromTextViolation(UnicodeTextValidator.Violation violation) {
            return switch (violation) {
                case CONTROL_CHARACTER -> CONTROL_CHARACTER;
                case UNSUPPORTED_CHARACTER -> UNSUPPORTED_CHARACTER;
            };
        }
    }
}
