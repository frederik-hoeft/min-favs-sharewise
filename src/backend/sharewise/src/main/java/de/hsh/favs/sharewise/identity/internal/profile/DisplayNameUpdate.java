package de.hsh.favs.sharewise.identity.internal.profile;

import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;

/** A validated display-name assignment; null explicitly means clear. */
public final class DisplayNameUpdate {

    private final String value;

    private DisplayNameUpdate(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Result<DisplayNameUpdate, ValidationErrors> tryFrom(String value) {
        if (value == null) {
            return Result.ok(new DisplayNameUpdate(null));
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > 80 || !validText(value)) {
            return Result.err(ValidationErrors.single("displayName", "invalid",
                    "Display name must contain 1–80 Unicode characters, including non-whitespace text, "
                    + "without control characters or line separators."));
        }
        return Result.ok(new DisplayNameUpdate(value));
    }

    private static boolean validText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return value.codePoints().anyMatch(codePoint -> !Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint))
                && value.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                    || codePoint == 0x2028 || codePoint == 0x2029);
    }
}
