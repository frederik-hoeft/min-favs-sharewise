package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationModelsTests {

    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");
    private final UUID userId = UUID.fromString("019ab800-0000-7000-8000-000000000001");
    private final UUID sessionId = UUID.fromString("019ab800-0000-7000-8000-000000000002");

    @Test
    void accountSnapshotPreservesCanonicalEmailAndStateWithoutPrintingPasswordVerifier() {
        AccountSnapshot account = new AccountSnapshot(
                userId, EmailAddress.parse("Alice@Example.com"), "bcrypt-secret-verifier", now,
                null, new LoginLockoutPolicy.State(2, null));
        assertEquals("alice@example.com", account.email().normalized());
        assertEquals(2, account.loginFailures().failedAttempts());
        assertFalse(account.isDisabled());
        assertFalse(account.toString().contains("bcrypt-secret-verifier"));
        assertFalse(account.toString().contains("Alice@Example.com"));

        AccountSnapshot disabled = new AccountSnapshot(
                userId, account.email(), account.encodedPassword(), now,
                now.plusSeconds(1), account.loginFailures());
        assertTrue(disabled.isDisabled());
        assertThrows(IllegalArgumentException.class,
                () -> new AccountSnapshot(userId, account.email(), " ", now,
                        null, account.loginFailures()));
    }

    @Test
    void sessionIdentityRemainsStableForIssuanceAndRotation() {
        SessionPolicy.Deadlines deadlines = SessionPolicy.initial().startAt(now);
        LoginSession session = new LoginSession(sessionId, userId, deadlines, null);
        assertEquals(sessionId, session.id());
        assertEquals(userId, session.userId());
        assertEquals(deadlines, session.deadlines());
        assertThrows(NullPointerException.class,
                () -> new LoginSession(null, userId, deadlines, null));
    }

    @Test
    void failureOutcomesAreTypedAndLoginDenialDoesNotExposeAccountState() {
        assertEquals(RegistrationFailure.EMAIL_UNAVAILABLE, RegistrationFailure.valueOf("EMAIL_UNAVAILABLE"));
        assertEquals(1, LoginFailure.values().length);
        assertEquals(LoginFailure.INVALID_CREDENTIALS, LoginFailure.values()[0]);
    }
}
