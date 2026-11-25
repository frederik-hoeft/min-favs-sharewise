package de.hsh.favs.sharewise.shared.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationTokenParserTests {

    private final ApplicationTokenParser parser = new ApplicationTokenParser();

    private static final UUID SELECTOR = UUID.fromString("019ab800-0000-7000-8000-000000000005");

    @Test
    void matchesOnlyTheCanonicalSecretAndMatchingSelector() {
        ApplicationTokenGenerator generator = new ApplicationTokenGenerator();
        ApplicationTokenGenerator.GeneratedToken credential = generator.generate(SELECTOR);
        ApplicationToken parsed = parser.parse(
                credential.value()).orElseThrow();
        assertTrue(parsed.matches(SELECTOR, credential.verifierHash()));
        assertFalse(parsed.toString().contains(credential.value()));
        assertFalse(parser.parse(generator.generate(SELECTOR).value())
                .orElseThrow().matches(SELECTOR, credential.verifierHash()));
        assertFalse(parsed.matches(UUID.randomUUID(), credential.verifierHash()));
        assertFalse(parsed.matches(SELECTOR, new byte[31]));
        assertFalse(parsed.matches(SELECTOR, null));
    }

    @Test
    void rejectsMalformedNoncanonicalOrOverlongValues() {
        ApplicationTokenGenerator.GeneratedToken credential =
                new ApplicationTokenGenerator().generate(SELECTOR);
        String valid = credential.value();
        String secret = valid.substring(37);
        byte[] decoded = Base64.getUrlDecoder().decode(secret);
        assertTrue(parser.parse(valid).isPresent());
        // The last base64url character contains two unused bits. Some decoders
        // accept a nonzero value for those bits as an alias of the same secret.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char alias = alphabet.charAt(alphabet.indexOf(secret.charAt(42)) + 1);
        for (String invalid : new String[] {
                null, "", " ", SELECTOR.toString(), "garbage", valid + "A", " " + valid,
                SELECTOR.toString().toUpperCase() + "." + secret,
                SELECTOR + "." + secret.substring(1), SELECTOR + "." + secret + "=",
                SELECTOR + "." + secret.substring(0, 42) + "!",
                SELECTOR + "." + secret.substring(0, 42) + alias,
                SELECTOR + "." + Base64.getUrlEncoder().encodeToString(decoded)
        }) {
            assertTrue(parser.parse(invalid).isEmpty(), invalid);
        }
    }

    @Test
    void acceptsPreviouslyIssuedRefreshWireFormatAndVerifier() {
        // Fixed independent vector: SHA-256 of 32 zero bytes, no generator involved.
        String value = SELECTOR + "." + "A".repeat(43);
        byte[] verifier = HexFormat.of().parseHex(
                "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925");
        ApplicationToken token = parser.parse(value).orElseThrow();
        assertEquals(SELECTOR, token.selector());
        assertTrue(token.matches(SELECTOR, verifier));
    }
}
