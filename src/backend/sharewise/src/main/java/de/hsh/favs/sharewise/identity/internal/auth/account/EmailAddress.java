package de.hsh.favs.sharewise.identity.internal.auth.account;

import java.util.Locale;
import java.util.regex.Pattern;

/** User-facing address and its unique-lookup key. Equality follows canonical identity. */
public final class EmailAddress {

    private static final int MAX_CHARACTERS = 256;

    // Regex adapts the supplied dot-atom/quoted-local example for this application.
    // The domain is restricted to 1-63-character host-style labels without leading
    // or trailing hyphens, not arbitrary RFC address literals.
    // This is syntax validation, not proof that the mailbox or domain exists.
    private static final String INTERNATIONAL =
            "\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}";
    private static final String ATOM = "[a-z\\d!#$%&'*+\\-/=?^_`{|}~" + INTERNATIONAL + "]";
    private static final String QUOTED_CHAR = "[\\x21\\x23-\\x5B\\x5D-\\x7E" + INTERNATIONAL + "]";
    private static final String ESCAPED_CHAR = "\\\\[\\x21-\\x7E" + INTERNATIONAL + "]";
    private static final String LOCAL_PART = "(?:" + ATOM + "+(?:\\." + ATOM + "+)*|\"(?:"
            + QUOTED_CHAR + "|" + ESCAPED_CHAR + ")+\")";
    private static final String DOMAIN_CHAR = "[a-z\\d\\p{L}\\p{N}]";
    private static final String DOMAIN_MIDDLE = "[a-z\\d\\p{L}\\p{N}-]";
    private static final String DOMAIN_LABEL = DOMAIN_CHAR + "(?:" + DOMAIN_MIDDLE + "{0,61}"
            + DOMAIN_CHAR + ")?";
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "\\A" + LOCAL_PART + "@" + DOMAIN_LABEL + "(?:\\." + DOMAIN_LABEL + ")+\\z",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final String display;
    private final String normalized;

    private EmailAddress(String display, String normalized) {
        this.display = display;
        this.normalized = normalized;
    }

    public static EmailAddress parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid email address");
        }
        String trimmed = value.strip();
        String canonical = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || length(trimmed) > MAX_CHARACTERS
                || length(canonical) > MAX_CHARACTERS
                || trimmed.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint)
                        || Character.getType(codePoint) == Character.FORMAT)
                || !ADDRESS_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
        return new EmailAddress(trimmed, canonical);
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    public String display() {
        return display;
    }

    public String normalized() {
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof EmailAddress that
                && normalized.equals(that.normalized));
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }

    @Override
    public String toString() {
        return "EmailAddress[redacted]";
    }
}
