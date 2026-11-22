package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** End-to-end PostgreSQL 18 proof of refresh/logout persistence, replay and transaction serialization. */
@Tag("e2e")
class SessionLifecycleE2E extends E2ETestBase {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void rotationCommitsHistoryAndOneSuccessorWithoutMovingTheAbsoluteDeadline() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            SessionDeadlines originalDeadlines = deadlines(fixture, initial.sessionId());
            // Force a shorter valid idle deadline; refresh must restore the policy window.
            execute(fixture, "update identity.sessions set idle_expires_at = ? where id = ?",
                    NOW.plusSeconds(10), initial.sessionId());
            assertEquals(NOW.plusSeconds(10), deadlines(fixture, initial.sessionId()).idle());
            AuthenticationResult rotated = successful(refresh(dispatcher, initial.refreshCredential().cookieValue(),
                    initial.sessionId()));
            assertEquals(originalDeadlines, deadlines(fixture, initial.sessionId()));

            assertEquals(initial.userId(), rotated.userId());
            assertEquals(initial.sessionId(), rotated.sessionId());
            assertNotEquals(initial.refreshCredential().tokenId(), rotated.refreshCredential().tokenId());
            assertEquals(initial.refreshExpiresAt(), rotated.refreshExpiresAt());
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens where consumed_at is null"));
            assertEquals(2, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertTrue(consumed(fixture, initial.refreshCredential().tokenId()));
            assertFalse(consumed(fixture, rotated.refreshCredential().tokenId()));
            assertNull(revokedAt(fixture, initial.sessionId()));
            assertEquals(7, rotated.refreshCredential().tokenId().version());

            AuthenticationResult restored = successful(refresh(dispatcher,
                    rotated.refreshCredential().cookieValue(), null));
            assertEquals(initial.sessionId(), restored.sessionId());
            assertEquals(3, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens where consumed_at is null"));
        });
    }

    @Test
    void wrongSecretOrExpectedSessionMismatchDoesNotRevokeOrRotate() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            UUID wrongSession = UUID.randomUUID();
            String wrongSecret = new RefreshCredentialGenerator()
                    .generate(initial.refreshCredential().tokenId()).cookieValue();

            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, wrongSecret, initial.sessionId()));
            assertEquals(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED),
                    refresh(dispatcher, initial.refreshCredential().cookieValue(), wrongSession));
            assertEquals(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED),
                    logout(dispatcher, initial.refreshCredential().cookieValue(), wrongSession));
            assertNull(revokedAt(fixture, initial.sessionId()));
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertFalse(consumed(fixture, initial.refreshCredential().tokenId()));
            assertTrue(refresh(dispatcher, initial.refreshCredential().cookieValue(),
                    initial.sessionId()).isSuccess());
        });
    }

    @Test
    void verifiedReplayRevokesTheFamilyEvenWithAnUnrelatedExpectedSession() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            AuthenticationResult successor = successful(refresh(dispatcher,
                    initial.refreshCredential().cookieValue(), initial.sessionId()));

            // A guessed selector or a wrong secret for consumed history must not revoke the family.
            String impostor = new RefreshCredentialGenerator()
                    .generate(initial.refreshCredential().tokenId()).cookieValue();
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, impostor, initial.sessionId()));
            assertNull(revokedAt(fixture, initial.sessionId()));

            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, initial.refreshCredential().cookieValue(), UUID.randomUUID()));
            assertNotNull(revokedAt(fixture, initial.sessionId()));
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, successor.refreshCredential().cookieValue(), initial.sessionId()));
            assertEquals(2, count(fixture, "select count(*) from identity.refresh_tokens"));
            // Revocation preserves the successor verifier and history; it does not physically delete it.
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens where consumed_at is null"));
        });
    }

    @Test
    void logoutRevokesDurablyAndIsIdempotentWithoutChangingTokenHistory() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            assertEquals(Result.ok(LogoutStatus.NO_SESSION), logout(dispatcher, null, initial.sessionId()));
            assertEquals(Result.ok(LogoutStatus.REVOKED), logout(dispatcher,
                    initial.refreshCredential().cookieValue(), initial.sessionId()));
            Instant firstRevocation = revokedAt(fixture, initial.sessionId());
            assertEquals(NOW, firstRevocation);
            assertEquals(Result.ok(LogoutStatus.REVOKED), logout(dispatcher,
                    initial.refreshCredential().cookieValue(), initial.sessionId()));
            assertEquals(firstRevocation, revokedAt(fixture, initial.sessionId()));
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, initial.refreshCredential().cookieValue(), initial.sessionId()));
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens"));
        });
    }

    @Test
    void signerFailureRollsBackConsumptionAndSuccessorAndLeavesCredentialUsable() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            IamAuthenticationE2E.FakeIssuer issuer = fixture.context()
                    .getBean(IamAuthenticationE2E.FakeIssuer.class);
            issuer.fail.set(true);
            assertThrows(IllegalStateException.class, () -> refresh(dispatcher,
                    initial.refreshCredential().cookieValue(), initial.sessionId()));
            issuer.fail.set(false);

            assertFalse(consumed(fixture, initial.refreshCredential().tokenId()));
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertNull(revokedAt(fixture, initial.sessionId()));
            assertTrue(refresh(dispatcher, initial.refreshCredential().cookieValue(),
                    initial.sessionId()).isSuccess());
            assertEquals(2, count(fixture, "select count(*) from identity.refresh_tokens"));
        });
    }

    @Test
    void disabledAndExpiredSessionsCannotRefreshButCanBeRevoked() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            execute(fixture, "update identity.users set disabled_at = ? where id = ?", NOW, initial.userId());
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, initial.refreshCredential().cookieValue(), initial.sessionId()));
            assertFalse(consumed(fixture, initial.refreshCredential().tokenId()));
            assertEquals(Result.ok(LogoutStatus.REVOKED), logout(dispatcher,
                    initial.refreshCredential().cookieValue(), initial.sessionId()));
        });
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            // Respect the DB's deadline-after-creation constraints while simulating an expired session.
            execute(fixture, "update identity.sessions set created_at = ?, idle_expires_at = ? where id = ?",
                    NOW.minusSeconds(7200), NOW.minusSeconds(1), initial.sessionId());
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, initial.refreshCredential().cookieValue(), initial.sessionId()));
            assertFalse(consumed(fixture, initial.refreshCredential().tokenId()));
            assertEquals(Result.ok(LogoutStatus.REVOKED), logout(dispatcher,
                    initial.refreshCredential().cookieValue(), initial.sessionId()));
        });
    }

    @Test
    void twoConcurrentRefreshesSerializeAndVerifiedLoserRevokesTheFamily() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            String original = initial.refreshCredential().cookieValue();
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<AuthenticationResult, SessionLifecycleFailure>> a =
                    new FutureTask<>(() -> concurrentRefresh(dispatcher, start, original, initial.sessionId()));
            FutureTask<Result<AuthenticationResult, SessionLifecycleFailure>> b =
                    new FutureTask<>(() -> concurrentRefresh(dispatcher, start, original, initial.sessionId()));
            Thread.ofVirtual().start(a);
            Thread.ofVirtual().start(b);
            start.countDown();
            List<Result<AuthenticationResult, SessionLifecycleFailure>> results = List.of(
                    a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter(Result::isSuccess).count());
            assertEquals(1, results.stream().filter(
                    r -> r.equals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE))).count());
            assertNotNull(revokedAt(fixture, initial.sessionId()));
            assertEquals(2, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens where consumed_at is null"));
            assertTrue(consumed(fixture, initial.refreshCredential().tokenId()));
        });
    }

    @Test
    void parallelLogoutAndRefreshCannotProduceAnUnrevokedSuccessor() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            String original = initial.refreshCredential().cookieValue();
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<AuthenticationResult, SessionLifecycleFailure>> renewal =
                    new FutureTask<>(() -> concurrentRefresh(dispatcher, start, original, initial.sessionId()));
            FutureTask<Result<LogoutStatus, SessionLifecycleFailure>> signOut =
                    new FutureTask<>(() -> {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Logout start timed out");
                        }
                        return logout(dispatcher, original, initial.sessionId());
                    });
            Thread.ofVirtual().start(renewal);
            Thread.ofVirtual().start(signOut);
            start.countDown();

            Result<AuthenticationResult, SessionLifecycleFailure> renewalOutcome = renewal.get(20, TimeUnit.SECONDS);
            Result<LogoutStatus, SessionLifecycleFailure> logout = signOut.get(20, TimeUnit.SECONDS);
            assertEquals(Result.ok(LogoutStatus.REVOKED), logout);
            assertNotNull(revokedAt(fixture, initial.sessionId()));
            assertEquals(renewalOutcome.isSuccess() ? 2 : 1,
                    count(fixture, "select count(*) from identity.refresh_tokens"));
            assertEquals(1, count(fixture,
                    "select count(*) from identity.refresh_tokens where consumed_at is null"));
            if (renewalOutcome.isSuccess()) {
                AuthenticationResult issued = successful(renewalOutcome);
                assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                        refresh(dispatcher, issued.refreshCredential().cookieValue(), issued.sessionId()));
            } else {
                assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE), renewalOutcome);
            }
        });
    }

    @Test
    void conditionalWritesRefuseDoubleConsumptionAndDeadlineRegressionWithoutCommitting() {
        runLifecycleTest(fixture -> {
            OperationDispatcher dispatcher = dispatcher(fixture);
            AuthenticationResult initial = register(dispatcher);
            UUID sessionId = initial.sessionId();
            UUID tokenId = initial.refreshCredential().tokenId();

            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(PersistenceProbe.class,
                    probe -> probe.transactions().execute(() -> {
                        probe.lifecycle().lockSession(sessionId).orElseThrow();
                        probe.lifecycle().consume(tokenId, NOW);
                        probe.lifecycle().consume(tokenId, NOW);
                        return TransactionOutcome.commit(null);
                    })));
            assertFalse(consumed(fixture, tokenId));

            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(PersistenceProbe.class,
                    probe -> probe.transactions().execute(() -> {
                        probe.lifecycle().lockSession(sessionId).orElseThrow();
                        probe.lifecycle().extendIdle(sessionId, NOW.plusSeconds(1));
                        return TransactionOutcome.commit(null);
                    })));
            assertEquals(1, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertNull(revokedAt(fixture, sessionId));
            assertTrue(refresh(dispatcher, initial.refreshCredential().cookieValue(), sessionId).isSuccess());
        });
    }

    private void runLifecycleTest(TestCallbacks.Action<IntegrationTestContext> test) {
        runTest(test, ProductionEntityScanConfiguration.class,
                IamAuthenticationE2E.Fixtures.class, LifecycleFixtures.class);
    }

    private static AuthenticationResult register(OperationDispatcher dispatcher) {
        Result<AuthenticationResult, RegistrationFailure> result = dispatcher.dispatch(
                RegistrationService.class, service -> service.register(RegistrationCommand.from(
                        "refresh-e2e@example.org", "long-enough-password", PasswordPolicy.initial())));
        assertTrue(result.isSuccess());
        return ((Result.Ok<AuthenticationResult, RegistrationFailure>) result).value();
    }

    private static OperationDispatcher dispatcher(IntegrationTestContext fixture) {
        return fixture.context().getBean(OperationDispatcher.class);
    }

    private static Result<AuthenticationResult, SessionLifecycleFailure> refresh(
            OperationDispatcher dispatcher, String cookie, UUID expectedSessionId) {
        return dispatcher.dispatch(SessionLifecycleService.class,
                service -> service.refresh(cookie, expectedSessionId));
    }

    private static Result<LogoutStatus, SessionLifecycleFailure> logout(
            OperationDispatcher dispatcher, String cookie, UUID expectedSessionId) {
        return dispatcher.dispatch(SessionLifecycleService.class,
                service -> service.logout(cookie, expectedSessionId));
    }

    private static Result<AuthenticationResult, SessionLifecycleFailure> concurrentRefresh(
            OperationDispatcher dispatcher, CountDownLatch start, String cookie, UUID expected)
            throws InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent refresh start timed out");
        }
        return refresh(dispatcher, cookie, expected);
    }

    private static AuthenticationResult successful(Result<AuthenticationResult, SessionLifecycleFailure> result) {
        assertTrue(result.isSuccess());
        return ((Result.Ok<AuthenticationResult, SessionLifecycleFailure>) result).value();
    }

    private static int count(IntegrationTestContext fixture, String sql) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rows = query.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static boolean consumed(IntegrationTestContext fixture, UUID tokenId) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement sql = connection.prepareStatement(
                     "select consumed_at is not null from identity.refresh_tokens where id = ?")) {
            sql.setObject(1, tokenId);
            try (ResultSet rows = sql.executeQuery()) {
                assertTrue(rows.next());
                return rows.getBoolean(1);
            }
        }
    }

    private static SessionDeadlines deadlines(IntegrationTestContext fixture, UUID sessionId) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement sql = connection.prepareStatement(
                     "select idle_expires_at, absolute_expires_at from identity.sessions where id = ?")) {
            sql.setObject(1, sessionId);
            try (ResultSet rows = sql.executeQuery()) {
                assertTrue(rows.next());
                return new SessionDeadlines(rows.getTimestamp(1).toInstant(), rows.getTimestamp(2).toInstant());
            }
        }
    }

    record SessionDeadlines(Instant idle, Instant absolute) { }

    private static Instant revokedAt(IntegrationTestContext fixture, UUID sessionId) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement sql = connection.prepareStatement(
                     "select revoked_at from identity.sessions where id = ?")) {
            sql.setObject(1, sessionId);
            try (ResultSet rows = sql.executeQuery()) {
                assertTrue(rows.next());
                Timestamp value = rows.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }

    private static void execute(IntegrationTestContext fixture, String query, Object... values) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement sql = connection.prepareStatement(query)) {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                sql.setObject(index + 1, value instanceof Instant instant
                        ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : value);
            }
            assertEquals(1, sql.executeUpdate());
        }
    }

    record PersistenceProbe(Transactions transactions, SessionLifecycleStore lifecycle) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class LifecycleFixtures {
        @Bean
        @Scope("operation")
        PersistenceProbe persistenceProbe(Transactions transactions, SessionLifecycleStore sessions) {
            return new PersistenceProbe(transactions, sessions);
        }

        @Bean
        @Scope("operation")
        SessionLifecycleService sessionLifecycleService(Transactions transactions, SessionLifecycleStore sessions,
                RefreshTokenStore refreshTokens, RefreshCredentialGenerator credentials,
                SessionPolicy policy, IamAuthenticationE2E.FakeIssuer issuer, Clock clock) {
            return new SessionLifecycleService(transactions, sessions, refreshTokens, credentials,
                    policy, issuer, clock);
        }
    }
}
