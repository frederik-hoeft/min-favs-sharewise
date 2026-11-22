package de.hsh.favs.sharewise.identity.internal.auth.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.identity.internal.auth.AuthenticationResult;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshCredentialGeneratorTests {

    private final UUID tokenId = UUID.fromString("019ab800-0000-7000-8000-000000000001");
    private final UUID sessionId = UUID.fromString("019ab800-0000-7000-8000-000000000002");
    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void encodedOpaqueTokenUsesSelectorAndThirtyTwoRandomBytesHashedForStorage() throws Exception {
        RefreshCredentialGenerator generator = new RefreshCredentialGenerator();
        RefreshCredentialGenerator.GeneratedCredential first = generator.generate(tokenId);
        RefreshCredentialGenerator.GeneratedCredential second = generator.generate(tokenId);

        assertEquals(tokenId, first.tokenId());
        assertEquals(tokenId + ".", first.cookieValue().substring(0, 37));
        String encoded = first.cookieValue().substring(37);
        assertEquals(43, encoded.length());
        byte[] secret = Base64.getUrlDecoder().decode(encoded);
        assertEquals(32, secret.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(secret), first.verifierHash());
        assertNotEquals(first.cookieValue(), second.cookieValue());
        assertEquals(32, first.verifierHash().length);
        assertFalse(first.toString().contains(encoded));
    }

    @Test
    void persistenceVerifierIsDefensivelyCopiedBothWays() {
        RefreshCredentialGenerator.GeneratedCredential credential =
                new RefreshCredentialGenerator().generate(tokenId);
        byte[] original = credential.verifierHash();
        RefreshTokenRecord record = new RefreshTokenRecord(tokenId, sessionId, original, now, null);
        Arrays.fill(original, (byte) 0);
        byte[] returned = record.verifierHash();
        returned[0] ^= 127;
        assertArrayEquals(credential.verifierHash(), record.verifierHash());
        assertEquals(sessionId, record.sessionId());
        assertFalse(record.toString().contains(Arrays.toString(credential.verifierHash())));
        assertThrows(IllegalArgumentException.class,
                () -> new RefreshTokenRecord(tokenId, sessionId, new byte[31], now, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RefreshTokenRecord(tokenId, sessionId, credential.verifierHash(),
                        now, now.minusSeconds(1)));
    }

    @Test
    void testOnlyEntropyInjectionDoesNotCreateAProductionCredentialBean() {
        SecureRandom deterministic = new SecureRandom() {
            private int counter;
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) ++counter);
            }
        };
        RefreshCredentialGenerator generator = new RefreshCredentialGenerator(deterministic);
        byte[] first = Base64.getUrlDecoder().decode(generator.generate(tokenId).cookieValue().substring(37));
        byte[] second = Base64.getUrlDecoder().decode(generator.generate(tokenId).cookieValue().substring(37));
        assertEquals(1, first[0]);
        assertEquals(2, second[0]);
    }

    @Test
    void issuedCredentialModelsDoNotLeakTokensThroughToString() {
        IssuedAccessToken access = new IssuedAccessToken(
                "access-secret", now.plusSeconds(60));
        RefreshCredentialGenerator.GeneratedCredential refresh =
                new RefreshCredentialGenerator().generate(tokenId);
        AuthenticationResult result = new AuthenticationResult(
                sessionId, sessionId, access, refresh, now.plusSeconds(3600));
        assertFalse(access.toString().contains("access-secret"));
        assertFalse(result.toString().contains("access-secret"));
        assertFalse(result.toString().contains(refresh.cookieValue()));
        assertThrows(IllegalArgumentException.class,
                () -> new IssuedAccessToken("", now));
    }
}
