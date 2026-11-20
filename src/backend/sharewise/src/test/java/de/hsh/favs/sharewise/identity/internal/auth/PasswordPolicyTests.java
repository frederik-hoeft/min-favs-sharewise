package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PasswordPolicyTests {

    private final PasswordPolicy policy = PasswordPolicy.initial();

    @Test
    void registrationUsesCodePointsAndUtf8BytesWithoutNormalizing() {
        assertDoesNotThrow(() -> policy.validateRegistration("a".repeat(12)));
        assertDoesNotThrow(() -> policy.validateRegistration("à".repeat(12)));
        assertDoesNotThrow(() -> policy.validateRegistration("a".repeat(72)));
        assertDoesNotThrow(() -> policy.validateRegistration("🌍".repeat(18)));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateRegistration("🌍".repeat(19)));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateRegistration("a".repeat(11)));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateRegistration("a".repeat(73)));
    }

    @Test
    void loginMayUseLegacyShortPasswordsButNeverTruncatedOrMalformedInput() {
        assertDoesNotThrow(() -> policy.validateVerificationInput("legacy"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput(""));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput(null));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateVerificationInput("a".repeat(73)));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateVerificationInput("abc\uD800"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateVerificationInput("abc\uDC00"));
    }

    @Test
    void immutableCommandsCarryCanonicalEmailAndDoNotLeakPasswords() {
        RegistrationCommand registration = RegistrationCommand.from(
                " One@Example.COM ", "aSecretPassword", policy);
        LoginCommand login = LoginCommand.from(" ONE@example.com ", "legacy", policy);
        assertEquals(registration.email(), login.email());
        assertEquals("aSecretPassword", registration.password());
        assertFalse(registration.toString().contains("aSecretPassword"));
        assertFalse(login.toString().contains("legacy"));
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationCommand.from("user@example.com", "short", policy));
    }

    @Test
    void invalidPolicyFailsBeforeItCanBeUsed() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy(0, 72));
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy(13, 12));
    }
}
