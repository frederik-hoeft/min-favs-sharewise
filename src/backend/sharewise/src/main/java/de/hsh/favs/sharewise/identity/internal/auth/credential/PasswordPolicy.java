package de.hsh.favs.sharewise.identity.internal.auth.credential;

import java.text.Normalizer;

/** Registration and verification input limits for NFC-normalized Unicode passwords. */
public record PasswordPolicy(int minimumCharacters, int maximumCharacters) {

    public PasswordPolicy {
        if (minimumCharacters < 1 || maximumCharacters < minimumCharacters) {
            throw new IllegalArgumentException("Invalid password policy");
        }
    }

    public static PasswordPolicy initial() {
        return new PasswordPolicy(15, 256);
    }

    public void validateRegistration(String password) {
        String candidate = normalized(password);
        if (candidate.codePointCount(0, candidate.length()) < minimumCharacters) {
            throw new IllegalArgumentException("Invalid password length");
        }
    }

    /** Login may verify older, shorter passwords; both paths reject malformed or oversized input. */
    public void validateVerificationInput(String password) {
        normalized(password);
    }

    /** Never trim, truncate or case-fold a password. Reject unpaired UTF-16 surrogates. */
    public String normalized(String password) {
        // Bound work before NFC normalization, which is otherwise applied to an
        // attacker-controlled input. A valid 256-code-point NFC value is well
        // below this raw UTF-16 limit, including supplementary characters.
        if (password == null || password.isEmpty() || password.length() > 2048) {
            throw new IllegalArgumentException("Invalid password length");
        }
        for (int i = 0; i < password.length(); i++) {
            char current = password.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= password.length() || !Character.isLowSurrogate(password.charAt(i))) {
                    throw new IllegalArgumentException("Invalid password encoding");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Invalid password encoding");
            }
        }
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) > maximumCharacters) {
            throw new IllegalArgumentException("Invalid password length");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.getType(codePoint) == Character.CONTROL
                    || Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("Invalid password characters");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }
}
