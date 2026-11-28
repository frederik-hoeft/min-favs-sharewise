package de.hsh.favs.sharewise.groups.internal.domain;

import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.UnicodeTextValidator;
import java.util.Objects;
import java.util.Optional;

/**
 * A trimmed, non-blank group name of at most {@value #MAX_CODE_POINTS} Unicode code points
 * with supported Unicode text and at least one letter or number. Names are not unique.
 *
 * @param value the normalized name
 */
public record GroupName(String value) {

    /** Maximum length, matching the {@code varchar(64)} column, which counts characters. */
    public static final int MAX_CODE_POINTS = 64;

    public GroupName {
        Objects.requireNonNull(value, "value");
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("Group name must be trimmed");
        }
        Optional<MetadataViolation.Reason> violation = violationOf(value, value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException("Invalid group name: " + violation.get());
        }
    }

    /** Trims and validates untrusted input. */
    public static Result<GroupName, MetadataViolation> parse(String raw) {
        String normalized = raw == null ? "" : raw.strip();
        Optional<MetadataViolation.Reason> violation = violationOf(raw == null ? "" : raw, normalized);
        if (violation.isPresent()) {
            return Result.err(new MetadataViolation(MetadataViolation.Field.NAME, violation.get()));
        }
        return Result.ok(new GroupName(normalized));
    }

    private static Optional<MetadataViolation.Reason> violationOf(String raw, String normalized) {
        if (normalized.isEmpty()) {
            return Optional.of(MetadataViolation.Reason.REQUIRED);
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            return Optional.of(MetadataViolation.Reason.TOO_LONG);
        }
        Optional<MetadataViolation.Reason> textViolation = UnicodeTextValidator.INSTANCE.validate(raw, false)
                .map(MetadataViolation.Reason::fromTextViolation);
        if (textViolation.isPresent()) return textViolation;
        if (!UnicodeTextValidator.INSTANCE.hasLetterOrNumber(normalized)) {
            return Optional.of(MetadataViolation.Reason.UNSUPPORTED_CHARACTER);
        }
        return Optional.empty();
    }
}
