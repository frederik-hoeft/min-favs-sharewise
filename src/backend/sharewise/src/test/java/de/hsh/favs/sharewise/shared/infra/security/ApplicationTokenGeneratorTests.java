package de.hsh.favs.sharewise.shared.infra.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationTokenGeneratorTests {

    private final UUID tokenId = UUID.fromString("019ab800-0000-7000-8000-000000000001");

    @Test
    void encodedOpaqueTokenUsesSelectorAndThirtyTwoRandomBytesHashedForStorage() throws Exception {
        ApplicationTokenGenerator generator = new ApplicationTokenGenerator();
        ApplicationTokenGenerator.GeneratedToken first = generator.generate(tokenId);
        ApplicationTokenGenerator.GeneratedToken second = generator.generate(tokenId);

        assertEquals(tokenId, first.selector());
        assertEquals(tokenId + ".", first.value().substring(0, 37));
        String encoded = first.value().substring(37);
        assertEquals(43, encoded.length());
        byte[] secret = Base64.getUrlDecoder().decode(encoded);
        assertEquals(32, secret.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(secret), first.verifierHash());
        assertNotEquals(first.value(), second.value());
        assertEquals(32, first.verifierHash().length);
        assertFalse(first.toString().contains(encoded));
    }

    @Test
    void eachGenerationUsesFreshRandomBytes() {
        SecureRandom deterministic = new SecureRandom() {
            private int counter;
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) ++counter);
            }
        };
        ApplicationTokenGenerator generator = new ApplicationTokenGenerator(deterministic);
        byte[] first = Base64.getUrlDecoder().decode(generator.generate(tokenId).value().substring(37));
        byte[] second = Base64.getUrlDecoder().decode(generator.generate(tokenId).value().substring(37));
        assertEquals(1, first[0]);
        assertEquals(2, second[0]);
    }

    @Test
    void generatedVerifierIsDefensivelyCopied() {
        ApplicationTokenGenerator.GeneratedToken generated = new ApplicationTokenGenerator().generate(tokenId);
        byte[] returned = generated.verifierHash();
        returned[0] ^= 127;
        assertNotEquals(returned[0], generated.verifierHash()[0]);
        assertTrue(new ApplicationTokenParser().parse(generated.value()).orElseThrow()
                .matches(tokenId, generated.verifierHash()));
        assertFalse(generated.toString().contains(Arrays.toString(generated.verifierHash())));
    }
}
