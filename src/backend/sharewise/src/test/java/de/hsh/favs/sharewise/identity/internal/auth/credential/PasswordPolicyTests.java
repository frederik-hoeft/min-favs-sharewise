package de.hsh.favs.sharewise.identity.internal.auth.credential;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.identity.internal.auth.LoginCommand;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationCommand;
import org.junit.jupiter.api.Test;

class PasswordPolicyTests {

    private final PasswordPolicy policy = PasswordPolicy.initial();

    @Test
    void registrationUsesEightTo256Utf16CodeUnitsWithoutNormalizationOrTruncation() {
        assertDoesNotThrow(() -> policy.validateRegistration("a".repeat(8)));
        assertDoesNotThrow(() -> policy.validateRegistration("🌍".repeat(4))); // UTF-16 length 8
        assertDoesNotThrow(() -> policy.validateRegistration("🌍".repeat(128)));
        assertDoesNotThrow(() -> policy.validateRegistration("e\u0301".repeat(128)));
        assertDoesNotThrow(() -> policy.validateRegistration("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateRegistration("a".repeat(7)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateRegistration("🌍".repeat(3)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateRegistration("🌍".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateRegistration("e\u0301".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateRegistration("a".repeat(257)));
    }

    @Test
    void loginAllowsShortPasswordsButRejectsOversizedOrInvalidUtf16() {
        assertDoesNotThrow(() -> policy.validateVerificationInput("legacy"));
        assertDoesNotThrow(() -> policy.validateVerificationInput("🌍".repeat(128)));
        assertDoesNotThrow(() -> policy.validateVerificationInput("pass\nword"));
        assertDoesNotThrow(() -> policy.validateVerificationInput("  "));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput(""));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput(null));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput("a".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput("abc\uD800"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateVerificationInput("abc\uDC00"));
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
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy(0, 256));
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy(16, 15));
    }
}
