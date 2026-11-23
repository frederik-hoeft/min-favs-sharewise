package de.hsh.favs.sharewise.identity.internal.auth.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresentedRefreshCredentialTests {

    private static final UUID SELECTOR = UUID.fromString("019ab800-0000-7000-8000-000000000005");

    @Test
    void matchesOnlyTheCanonicalSecretAndMatchingSelector() {
        RefreshCredentialGenerator generator = new RefreshCredentialGenerator();
        RefreshCredentialGenerator.GeneratedCredential credential = generator.generate(SELECTOR);
        RefreshTokenRecord stored = new RefreshTokenRecord(SELECTOR, UUID.randomUUID(),
                credential.verifierHash(), Instant.now(), null);
        PresentedRefreshCredential parsed = PresentedRefreshCredential.parse(
                credential.cookieValue()).orElseThrow();
        assertTrue(parsed.matches(stored));
        assertFalse(parsed.toString().contains(credential.cookieValue()));
        assertFalse(PresentedRefreshCredential.parse(generator.generate(SELECTOR).cookieValue())
                .orElseThrow().matches(stored));
        assertFalse(PresentedRefreshCredential.parse(generator.generate(UUID.randomUUID()).cookieValue())
                .orElseThrow().matches(stored));
    }

    @Test
    void rejectsMalformedNoncanonicalOrOverlongCookieBeforeDatabaseLookup() {
        RefreshCredentialGenerator.GeneratedCredential credential =
                new RefreshCredentialGenerator().generate(SELECTOR);
        String valid = credential.cookieValue();
        String secret = valid.substring(37);
        byte[] decoded = Base64.getUrlDecoder().decode(secret);
        assertTrue(PresentedRefreshCredential.parse(valid).isPresent());
        for (String invalid : new String[] {
                "", " ", SELECTOR.toString(), "garbage", valid + "A", " " + valid,
                SELECTOR.toString().toUpperCase() + "." + secret,
                SELECTOR + "." + secret.substring(1), SELECTOR + "." + secret + "=",
                SELECTOR + "." + secret.substring(0, 42) + "!",
                SELECTOR + "." + Base64.getUrlEncoder().encodeToString(decoded)
        }) {
            assertTrue(PresentedRefreshCredential.parse(invalid).isEmpty(), invalid);
        }
    }
}
