package de.hsh.favs.sharewise.identity.internal.auth;

import java.util.Locale;

/** User-facing address and its unique-lookup key. Equality follows canonical identity. */
public final class EmailAddress {

    private static final int MAX_CHARACTERS = 256;
    private static final String LOCAL_SPECIALS = "!#$%&'*+-/=?^_`{|}~";

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
                || length(canonical) > MAX_CHARACTERS) {
            throw new IllegalArgumentException("Invalid email address");
        }
        int separator = trimmed.indexOf('@');
        if (separator < 1 || separator != trimmed.lastIndexOf('@') || separator == trimmed.length() - 1) {
            throw new IllegalArgumentException("Invalid email address");
        }
        String local = trimmed.substring(0, separator);
        String domain = trimmed.substring(separator + 1);
        if (!validLocal(local) || !validDomain(domain)) {
            throw new IllegalArgumentException("Invalid email address");
        }
        return new EmailAddress(trimmed, canonical);
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean validLocal(String local) {
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        return local.codePoints().allMatch(codePoint -> Character.isLetterOrDigit(codePoint)
                || codePoint == '.' || (codePoint < 128 && LOCAL_SPECIALS.indexOf(codePoint) >= 0));
    }

    private static boolean validDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty() || length(label) > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            if (!label.codePoints().allMatch(codePoint -> Character.isLetterOrDigit(codePoint)
                    || codePoint == '-')) {
                return false;
            }
        }
        return true;
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
