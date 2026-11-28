package de.hsh.favs.sharewise.groups.internal.domain;

import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.UnicodeTextValidator;
import java.util.Objects;
import java.util.Optional;

/**
 * A trimmed, non-empty group description of at most {@value #MAX_CODE_POINTS} Unicode code
 * points. Line breaks ({@code \n} and {@code \r\n}) are allowed; unsupported Unicode characters are not.
 *
 * @param value the normalized description
 */
public record GroupDescription(String value) {

    /** Maximum length, matching the {@code varchar(512)} column, which counts characters. */
    public static final int MAX_CODE_POINTS = 512;

    public GroupDescription {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Group description must be trimmed and non-empty");
        }
        Optional<MetadataViolation.Reason> violation = violationOf(value, value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException("Invalid group description: " + violation.get());
        }
    }

    /** Trims and validates untrusted input; a missing or blank description is absent. */
    public static Result<Optional<GroupDescription>, MetadataViolation> parse(String raw) {
        String normalized = raw == null ? "" : raw.strip();
        Optional<MetadataViolation.Reason> violation = violationOf(raw == null ? "" : raw, normalized);
        if (violation.isPresent()) {
            return Result.err(new MetadataViolation(MetadataViolation.Field.DESCRIPTION, violation.get()));
        }
        return Result.ok(normalized.isEmpty() ? Optional.empty() : Optional.of(new GroupDescription(normalized)));
    }

    private static Optional<MetadataViolation.Reason> violationOf(String raw, String normalized) {
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            return Optional.of(MetadataViolation.Reason.TOO_LONG);
        }
        return UnicodeTextValidator.INSTANCE.validate(raw, true).map(MetadataViolation.Reason::fromTextViolation);
    }
}
