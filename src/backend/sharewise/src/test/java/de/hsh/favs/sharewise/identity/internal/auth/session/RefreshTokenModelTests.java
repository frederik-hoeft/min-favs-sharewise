package de.hsh.favs.sharewise.identity.internal.auth.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.identity.internal.auth.AuthenticationResult;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenModelTests {

    private final UUID tokenId = UUID.fromString("019ab800-0000-7000-8000-000000000001");
    private final UUID sessionId = UUID.fromString("019ab800-0000-7000-8000-000000000002");
    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void persistenceVerifierIsDefensivelyCopiedBothWays() {
        ApplicationTokenGenerator.GeneratedToken credential =
                new ApplicationTokenGenerator().generate(tokenId);
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
    void issuedCredentialModelsDoNotLeakTokensThroughToString() {
        IssuedAccessToken access = new IssuedAccessToken(
                "access-secret", now.plusSeconds(60));
        ApplicationTokenGenerator.GeneratedToken refresh =
                new ApplicationTokenGenerator().generate(tokenId);
        AuthenticationResult result = new AuthenticationResult(
                sessionId, sessionId, access, refresh, now.plusSeconds(3600));
        assertFalse(access.toString().contains("access-secret"));
        assertFalse(result.toString().contains("access-secret"));
        assertFalse(result.toString().contains(refresh.value()));
        assertThrows(IllegalArgumentException.class,
                () -> new IssuedAccessToken("", now));
    }
}
