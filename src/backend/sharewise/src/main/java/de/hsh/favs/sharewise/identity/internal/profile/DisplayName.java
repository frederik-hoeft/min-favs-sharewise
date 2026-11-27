package de.hsh.favs.sharewise.identity.internal.profile;

import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import de.hsh.favs.sharewise.shared.validation.UnicodeTextValidator;

/** A mandatory, validated display name. */
public final class DisplayName {

    private final String value;

    private DisplayName(String value) {
        this.value = value;
    }

    public static DisplayName from(String value) {
        return tryFrom(value).fold(name -> name, errors -> { throw new IllegalArgumentException("Invalid display name"); });
    }

    public String value() {
        return value;
    }

    public static Result<DisplayName, ValidationErrors> tryFrom(String value) {
        if (value == null) {
            return Result.err(ValidationErrors.single("displayName", "required", "Display name is required."));
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > 80 || UnicodeTextValidator.INSTANCE.validate(value, false).isPresent()
                || !UnicodeTextValidator.INSTANCE.hasLetterOrNumber(value)) {
            return Result.err(ValidationErrors.single("displayName", "invalid",
                    "Enter 1–80 characters of supported text, including a letter or number."));
        }
        return Result.ok(new DisplayName(value));
    }

}
