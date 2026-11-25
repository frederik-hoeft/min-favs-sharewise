package de.hsh.favs.sharewise.groups.internal.domain;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;

/**
 * A trimmed, non-empty group description of at most {@value #MAX_CODE_POINTS} Unicode code
 * points. Line breaks ({@code \n} and {@code \r\n}) are allowed; other control characters are not.
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
        Optional<MetadataViolation.Reason> violation = violationOf(value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException("Invalid group description: " + violation.get());
        }
    }

    /** Trims and validates untrusted input; a missing or blank description is absent. */
    public static Result<Optional<GroupDescription>, MetadataViolation> parse(String raw) {
        String normalized = raw == null ? "" : raw.strip();
        if (normalized.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        Optional<MetadataViolation.Reason> violation = violationOf(normalized);
        if (violation.isPresent()) {
            return Result.err(new MetadataViolation(MetadataViolation.Field.DESCRIPTION, violation.get()));
        }
        return Result.ok(Optional.of(new GroupDescription(normalized)));
    }

    private static Optional<MetadataViolation.Reason> violationOf(String normalized) {
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            return Optional.of(MetadataViolation.Reason.TOO_LONG);
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            boolean lineBreak = character == '\n'
                || (character == '\r' && index + 1 < normalized.length() && normalized.charAt(index + 1) == '\n');
            if (Character.isISOControl(character) && !lineBreak) {
                return Optional.of(MetadataViolation.Reason.CONTROL_CHARACTER);
            }
        }
        return Optional.empty();
    }
}
