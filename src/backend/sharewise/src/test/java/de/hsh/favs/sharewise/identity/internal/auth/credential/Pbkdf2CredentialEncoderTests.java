package de.hsh.favs.sharewise.identity.internal.auth.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

/** Verify the JDK-backed Spring encoder's initial fixed PBKDF2 profile. */
class Pbkdf2CredentialEncoderTests {
    private final Pbkdf2CredentialEncoder encoder = new Pbkdf2CredentialEncoder(PasswordPolicy.initial());

    @Test
    void derivesSha256SubkeyWithRandom128BitSaltAndRetainsExactUnicodeInput() throws Exception {
        String password = "e\u0301".repeat(40) + "🌍".repeat(70);
        String encoded = encoder.encode(password);
        byte[] stored = HexFormat.of().parseHex(encoded);
        assertTrue(encoded.matches("[0-9a-f]{96}"));
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.matches("é".repeat(40) + "🌍".repeat(70), encoded));
        assertFalse(encoder.matches("e".repeat(40) + "🌍".repeat(70), encoded));

        byte[] salt = java.util.Arrays.copyOfRange(stored, 0, Pbkdf2CredentialEncoder.SALT_BYTES);
        byte[] subkey = java.util.Arrays.copyOfRange(stored, Pbkdf2CredentialEncoder.SALT_BYTES, stored.length);
        assertArrayEquals(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(new PBEKeySpec(password.toCharArray(), salt,
                                Pbkdf2CredentialEncoder.ITERATIONS,
                                Pbkdf2CredentialEncoder.DERIVED_KEY_BYTES * Byte.SIZE)).getEncoded(),
                subkey);
    }

    @Test
    void usesIndependentSaltsAndRejectsInvalidInputsAndForeignFormats() {
        String password = "some-valid-password";
        String encoded = encoder.encode(password);
        assertNotEquals(encoded, encoder.encode(password));
        assertFalse(encoder.matches("incorrect-password", encoded));
        assertFalse(encoder.matches(password, null));
        assertFalse(encoder.matches(password, "not-an-encoded-hash"));
        assertFalse(encoder.matches(password, "{bcrypt}$2a$..."));
        assertFalse(encoder.matches(password, "$argon2id$v=19$m=65536,t=3,p=4$..."));
        assertFalse(encoder.matches(password, "a".repeat(94)));
        assertFalse(encoder.matches(password, "z".repeat(96)));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("a".repeat(7)));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("a".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> encoder.matches("abc\uD800", encoded));
    }
}
