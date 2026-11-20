package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailAddressTests {

    @Test
    void trimsAndCanonicalizesWithRootLocaleWithoutRewritingPresentation() {
        EmailAddress email = EmailAddress.parse("  Alice+News@Example.COM  ");
        EmailAddress equivalent = EmailAddress.parse("alice+news@example.com");

        assertEquals("Alice+News@Example.COM", email.display());
        assertEquals("alice+news@example.com", email.normalized());
        assertEquals(equivalent, email);
        assertEquals(equivalent.hashCode(), email.hashCode());
        assertNotEquals(email, EmailAddress.parse("alice+else@example.com"));
    }

    @Test
    void rootLocaleLowercasingMayExpandCanonicalKeyBeyondDisplayLimit() {
        String local = "İ".repeat(123);
        assertThrows(IllegalArgumentException.class,
                () -> EmailAddress.parse(local + "@example.com"));
    }

    @Test
    void acceptsBoundaryLengthWithoutTruncation() {
        String address = "a".repeat(128) + "@" + "b".repeat(63) + "." + "c".repeat(59) + ".io";
        // 128 + 1 + 63 + 1 + 59 + 3 = 255 characters.
        assertEquals(address, EmailAddress.parse(address).display());
        assertEquals(256, EmailAddress.parse(address + "z").display().length());
        assertThrows(IllegalArgumentException.class,
                () -> EmailAddress.parse(address + "z" + "z"));
    }

    @Test
    void rejectsMissingPartsMultipleAtSignsInvalidLabelsWhitespaceAndControlCharacters() {
        String[] invalid = {
            "", " ", "@example.com", "alice@", "alice@example", "a@@example.com",
            "alice@-example.com", "alice@example-.com", "alice@example..com",
            ".alice@example.com", "alice..b@example.com", "alice.@example.com",
            "alice (comment)@example.com", "alice\n@example.com", "a@exa mple.com",
            "alice@exa_mple.com", "alice@example.com\u0000"
        };
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> EmailAddress.parse(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.parse(null));
    }

    @Test
    void neverIncludesTheEmailInDiagnosticString() {
        EmailAddress email = EmailAddress.parse("private@example.com");
        assertEquals("EmailAddress[redacted]", email.toString());
    }
}
