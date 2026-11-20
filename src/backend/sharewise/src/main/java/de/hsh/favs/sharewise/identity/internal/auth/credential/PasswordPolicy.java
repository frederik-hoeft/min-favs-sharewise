package de.hsh.favs.sharewise.identity.internal.auth.credential;

import java.nio.charset.StandardCharsets;

/** Registration policy and bcrypt-safe verification input limits. Never normalizes passwords. */
public record PasswordPolicy(int minimumCharacters, int maximumUtf8Bytes) {

    public PasswordPolicy {
        if (minimumCharacters < 1 || maximumUtf8Bytes < minimumCharacters) {
            throw new IllegalArgumentException("Invalid password policy");
        }
    }

    public static PasswordPolicy initial() {
        return new PasswordPolicy(12, 72);
    }

    public void validateRegistration(String password) {
        validateVerificationInput(password);
        if (password.codePointCount(0, password.length()) < minimumCharacters) {
            throw new IllegalArgumentException("Invalid password length");
        }
    }

    /** Login permits previously issued shorter passwords, but never bcrypt-truncated input. */
    public void validateVerificationInput(String password) {
        if (password == null || password.isEmpty()) {
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
        if (password.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes) {
            throw new IllegalArgumentException("Invalid password length");
        }
    }
}
