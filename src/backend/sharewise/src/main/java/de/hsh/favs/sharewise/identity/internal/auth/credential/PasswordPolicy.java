package de.hsh.favs.sharewise.identity.internal.auth.credential;

/** Password input limits measured in Java UTF-16 code units (as in JavaScript String.length). */
public record PasswordPolicy(int minimumCharacters, int maximumCharacters) {

    public PasswordPolicy {
        if (minimumCharacters < 1 || maximumCharacters < minimumCharacters) {
            throw new IllegalArgumentException("Invalid password policy");
        }
    }

    public static PasswordPolicy initial() {
        return new PasswordPolicy(8, 256);
    }

    public void validateRegistration(String password) {
        validateVerificationInput(password);
        if (password.length() < minimumCharacters) {
            throw new IllegalArgumentException("Invalid password length");
        }
    }

    /** Allow older short passwords at login, but reject oversized or malformed input. */
    public void validateVerificationInput(String password) {
        if (password == null || password.isEmpty() || password.length() > maximumCharacters) {
            throw new IllegalArgumentException("Invalid password length");
        }
        // Invalid surrogate pairs do not have a unique UTF-8 encoding. Avoid replacement
        // characters accidentally making distinct input strings hash to the same value.
        for (int index = 0; index < password.length(); index++) {
            char current = password.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= password.length() || !Character.isLowSurrogate(password.charAt(index))) {
                    throw new IllegalArgumentException("Invalid password encoding");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Invalid password encoding");
            }
        }
    }
}
