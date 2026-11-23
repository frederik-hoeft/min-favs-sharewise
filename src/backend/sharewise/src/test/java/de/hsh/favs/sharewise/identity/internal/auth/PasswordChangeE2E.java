package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.test.context.TestConfiguration;

/** PostgreSQL 18 proof of credential replacement, durable session revocation and lock ordering. */
@Tag("e2e")
class PasswordChangeE2E extends E2ETestBase {
    private static final String ORIGINAL = "original-password-long";
    private static final String REPLACEMENT = "replacement-password-long";
    private record TestSession(UUID userId, UUID sessionId) { }
    private static final ThreadLocal<TestSession> TEST_SESSION = new ThreadLocal<>();

    @Test
    void successfulChangeKeepsOnlyCallerEligibleAndNewLoginsRequireReplacement() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult second = login(dispatcher, ORIGINAL);
            AuthenticationResult unrelated = registerOther(dispatcher);
            assertEquals(first.userId(), second.userId());
            assertEquals(Result.ok(PasswordChangeStatus.CHANGED),
                    change(dispatcher, first, ORIGINAL, REPLACEMENT));

            assertNull(revokedAt(fixture, first.sessionId()));
            assertNotNull(revokedAt(fixture, second.sessionId()));
            assertNull(revokedAt(fixture, unrelated.sessionId()));
            assertEquals(2, count(fixture, "select count(*) from identity.sessions where revoked_at is null"));
            assertEquals(3, count(fixture, "select count(*) from identity.refresh_tokens"));
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    refresh(dispatcher, second));
            assertTrue(refresh(dispatcher, first).isSuccess());
            assertTrue(refresh(dispatcher, unrelated).isSuccess());
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS),
                    dispatcher.dispatch(LoginService.class, service -> service.login(loginCommand(ORIGINAL))));
            assertTrue(dispatcher.dispatch(LoginService.class,
                    service -> service.login(loginCommand(REPLACEMENT))).isSuccess());
        });
    }

    @Test
    void incorrectPasswordCommitsLockoutCountersWithoutRevokingAnySession() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult second = login(dispatcher, ORIGINAL);
            for (int i = 1; i <= 5; i++) {
                assertEquals(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID),
                        change(dispatcher, first, "incorrect-password", REPLACEMENT));
                assertEquals(i, failureCount(fixture));
            }
            assertEquals(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID),
                    change(dispatcher, first, ORIGINAL, REPLACEMENT));
            assertNull(revokedAt(fixture, first.sessionId()));
            assertNull(revokedAt(fixture, second.sessionId()));
            assertEquals(ORIGINAL, originalVerifierNotModified(fixture));
        });
    }

    @Test
    void mismatchedRevokedAndExpiredSessionCannotChangePassword() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult unrelated = registerOther(dispatcher);
            assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                    withSession(new TestSession(first.userId(), unrelated.sessionId()),
                            () -> changeDirect(dispatcher, ORIGINAL, REPLACEMENT)));
            execute(fixture, "update identity.sessions set revoked_at = now() where id = ?",
                    first.sessionId());
            assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                    change(dispatcher, first, ORIGINAL, REPLACEMENT));
            AuthenticationResult another = login(dispatcher, ORIGINAL);
            execute(fixture, "update identity.sessions set created_at = ?, "
                    + "idle_expires_at = ? where id = ?",
                    Instant.parse("2026-11-19T10:00:00Z"), Instant.parse("2026-11-20T11:59:59Z"),
                    another.sessionId());
            assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                    change(dispatcher, another, ORIGINAL, REPLACEMENT));
        });
    }

    @Test
    void concurrentPasswordChangesHaveAtMostOneWinnerAndDoNotResurrectOldVerifier() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult second = login(dispatcher, ORIGINAL);
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<PasswordChangeStatus, PasswordChangeFailure>> left = new FutureTask<>(() ->
                    concurrentChange(dispatcher, start, first, ORIGINAL, REPLACEMENT));
            FutureTask<Result<PasswordChangeStatus, PasswordChangeFailure>> right = new FutureTask<>(() ->
                    concurrentChange(dispatcher, start, first, ORIGINAL, "another-replacement-password"));
            Thread.ofVirtual().start(left);
            Thread.ofVirtual().start(right);
            start.countDown();
            Result<PasswordChangeStatus, PasswordChangeFailure> a = left.get(20, TimeUnit.SECONDS);
            Result<PasswordChangeStatus, PasswordChangeFailure> b = right.get(20, TimeUnit.SECONDS);
            assertEquals(1, java.util.List.of(a, b).stream().filter(Result::isSuccess).count());
            assertFalse(dispatcher.dispatch(LoginService.class,
                    service -> service.login(loginCommand(ORIGINAL))).isSuccess());
            assertNotNull(revokedAt(fixture, second.sessionId()));
        });
    }

    @Test
    void racingLoginAndRefreshCannotProduceAnEligibleOldPasswordSession() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult second = login(dispatcher, ORIGINAL);
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<AuthenticationResult, LoginFailure>> login = new FutureTask<>(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return dispatcher.dispatch(LoginService.class,
                        service -> service.login(loginCommand(ORIGINAL)));
            });
            FutureTask<Result<AuthenticationResult, SessionLifecycleFailure>> refresh = new FutureTask<>(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return refresh(dispatcher, second);
            });
            Thread.ofVirtual().start(login);
            Thread.ofVirtual().start(refresh);
            start.countDown();
            assertEquals(Result.ok(PasswordChangeStatus.CHANGED),
                    change(dispatcher, first, ORIGINAL, REPLACEMENT));
            Result<AuthenticationResult, LoginFailure> loginResult = login.get(20, TimeUnit.SECONDS);
            Result<AuthenticationResult, SessionLifecycleFailure> refreshed = refresh.get(20, TimeUnit.SECONDS);
            if (loginResult.isSuccess()) {
                AuthenticationResult loggedIn = ((Result.Ok<AuthenticationResult, LoginFailure>) loginResult).value();
                assertNotNull(revokedAt(fixture, loggedIn.sessionId()));
            }
            if (refreshed.isSuccess()) {
                AuthenticationResult renewed = ((Result.Ok<AuthenticationResult, SessionLifecycleFailure>) refreshed).value();
                assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                        refresh(dispatcher, renewed));
            }
            assertNotNull(revokedAt(fixture, second.sessionId()));
            assertNull(revokedAt(fixture, first.sessionId()));
        });
    }

    @Test
    void racingCallerLogoutAndPasswordChangeHasNoRetainedRevokedSession() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult second = login(dispatcher, ORIGINAL);
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<PasswordChangeStatus, PasswordChangeFailure>> changing = new FutureTask<>(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return change(dispatcher, first, ORIGINAL, REPLACEMENT);
            });
            FutureTask<Result<LogoutStatus, SessionLifecycleFailure>> signingOut = new FutureTask<>(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return dispatcher.dispatch(SessionLifecycleService.class, service -> service.logout(
                        first.refreshCredential().cookieValue(), first.sessionId()));
            });
            Thread.ofVirtual().start(changing);
            Thread.ofVirtual().start(signingOut);
            start.countDown();
            Result<PasswordChangeStatus, PasswordChangeFailure> outcome = changing.get(20, TimeUnit.SECONDS);
            assertEquals(Result.ok(LogoutStatus.REVOKED), signingOut.get(20, TimeUnit.SECONDS));
            assertNotNull(revokedAt(fixture, first.sessionId()));
            if (outcome.isSuccess()) {
                // The password committed first; logout then revoked the previously retained caller.
                assertNotNull(revokedAt(fixture, second.sessionId()));
                assertFalse(dispatcher.dispatch(LoginService.class,
                        service -> service.login(loginCommand(ORIGINAL))).isSuccess());
            } else {
                // Logout committed first, so the password and other-session revocations abstained.
                assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE), outcome);
                assertNull(revokedAt(fixture, second.sessionId()));
            }
        });
    }

    @Test
    void failedTransactionRollsBackPasswordAndAllSessionWrites() {
        runPasswordTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher);
            AuthenticationResult second = login(dispatcher, ORIGINAL);
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(Probe.class,
                    probe -> probe.transactions().execute(() -> {
                        probe.accounts().findByIdForUpdate(first.userId()).orElseThrow();
                        probe.sessions().lockSession(first.sessionId()).orElseThrow();
                        probe.accounts().replacePassword(first.userId(), probe.encoder().encode(ORIGINAL),
                                probe.encoder().encode(REPLACEMENT));
                        probe.sessions().revokeOtherSessions(first.userId(), first.sessionId(),
                                probe.clock().instant());
                        throw new IllegalStateException("simulate rollback after all writes");
                    })));
            assertNull(revokedAt(fixture, second.sessionId()));
            assertEquals(0, failureCount(fixture));
            assertTrue(dispatcher.dispatch(LoginService.class,
                    service -> service.login(loginCommand(ORIGINAL))).isSuccess());
        });
    }

    private void runPasswordTest(TestCallbacks.Action<IntegrationTestContext> action) {
        runTest(action, ProductionEntityScanConfiguration.class,
                IamAuthenticationE2E.Fixtures.class, SessionLifecycleE2E.LifecycleFixtures.class,
                PasswordFixtures.class);
    }

    private static AuthenticationResult register(OperationDispatcher dispatcher) {
        Result<AuthenticationResult, RegistrationFailure> result = dispatcher.dispatch(RegistrationService.class,
                service -> service.register(RegistrationCommand.from(
                        "test-password-change@example.org", ORIGINAL, PasswordPolicy.initial())));
        return ((Result.Ok<AuthenticationResult, RegistrationFailure>) result).value();
    }

    private static AuthenticationResult registerOther(OperationDispatcher dispatcher) {
        Result<AuthenticationResult, RegistrationFailure> result = dispatcher.dispatch(RegistrationService.class,
                service -> service.register(RegistrationCommand.from(
                        "other-user@example.org", ORIGINAL, PasswordPolicy.initial())));
        return ((Result.Ok<AuthenticationResult, RegistrationFailure>) result).value();
    }

    private static LoginCommand loginCommand(String password) {
        return LoginCommand.from("test-password-change@example.org", password, PasswordPolicy.initial());
    }

    private static AuthenticationResult login(OperationDispatcher dispatcher, String password) {
        Result<AuthenticationResult, LoginFailure> result = dispatcher.dispatch(LoginService.class,
                service -> service.login(loginCommand(password)));
        return ((Result.Ok<AuthenticationResult, LoginFailure>) result).value();
    }

    private static Result<AuthenticationResult, SessionLifecycleFailure> refresh(OperationDispatcher dispatcher,
            AuthenticationResult result) {
        return dispatcher.dispatch(SessionLifecycleService.class,
                service -> service.refresh(result.refreshCredential().cookieValue(), result.sessionId()));
    }

    private static Result<PasswordChangeStatus, PasswordChangeFailure> change(OperationDispatcher dispatcher,
            AuthenticationResult caller, String current, String next) {
        return withSession(new TestSession(caller.userId(), caller.sessionId()),
                () -> changeDirect(dispatcher, current, next));
    }

    private static Result<PasswordChangeStatus, PasswordChangeFailure> changeDirect(OperationDispatcher dispatcher,
            String current, String next) {
        ChangePasswordCommand input = ((Result.Ok<ChangePasswordCommand, ?>)
                ChangePasswordCommand.tryFrom(current, next, PasswordPolicy.initial())).value();
        return dispatcher.dispatch(PasswordChangeService.class, service -> service.change(input));
    }

    private static Result<PasswordChangeStatus, PasswordChangeFailure> concurrentChange(
            OperationDispatcher dispatcher, CountDownLatch latch, AuthenticationResult caller,
            String current, String next) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timed out"); }
        return change(dispatcher, caller, current, next);
    }

    private static <T> T withSession(TestSession session, Supplier<T> action) {
        TEST_SESSION.set(session);
        try { return action.get(); } finally { TEST_SESSION.remove(); }
    }

    private static Instant revokedAt(IntegrationTestContext fixture, UUID sessionId) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement stmt = connection.prepareStatement(
                     "select revoked_at from identity.sessions where id = ?")) {
            stmt.setObject(1, sessionId);
            try (ResultSet rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                Timestamp value = rows.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }

    private static int count(IntegrationTestContext fixture, String query) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement stmt = connection.prepareStatement(query);
             ResultSet rows = stmt.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static int failureCount(IntegrationTestContext fixture) throws SQLException {
        return count(fixture, "select failed_login_attempts from identity.users");
    }

    private static String originalVerifierNotModified(IntegrationTestContext fixture) throws SQLException {
        // Compare independently against the same test fixture encoder, without exposing raw hashes.
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement stmt = connection.prepareStatement(
                     "select password_hash from identity.users where normalized_email = ?")) {
            stmt.setString(1, "test-password-change@example.org");
            try (ResultSet rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                String hash = rows.getString(1);
                assertNotNull(hash);
                return hash.equals(new IamAuthenticationE2E.FakeEncoder().encode(ORIGINAL)) ? ORIGINAL : "changed";
            }
        }
    }

    private static void execute(IntegrationTestContext fixture, String query, Object... parameters) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement stmt = connection.prepareStatement(query)) {
            for (int index = 0; index < parameters.length; index++) {
                Object parameter = parameters[index];
                stmt.setObject(index + 1, parameter instanceof Instant instant
                        ? java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) : parameter);
            }
            assertEquals(1, stmt.executeUpdate());
        }
    }

    record Probe(Transactions transactions, AccountStore accounts, SessionLifecycleStore sessions,
            IamAuthenticationE2E.FakeEncoder encoder, Clock clock) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordFixtures {
        @Bean
        @Primary
        @Scope("operation")
        IdentityContext testIdentityContext() {
            TestSession session = TEST_SESSION.get();
            if (session == null) { throw new IllegalStateException("Missing test primary session evidence"); }
            return new IdentityContext() {
                @Override public java.util.Optional<UUID> userId() { return java.util.Optional.of(session.userId()); }
                @Override public java.util.Optional<String> sessionId() { return java.util.Optional.of(session.sessionId().toString()); }
            };
        }
        @Bean
        @Scope("operation")
        PasswordChangeService passwordChangeService(IdentityContext identity, Transactions transactions,
                AccountStore accounts, SessionLifecycleStore sessions,
                IamAuthenticationE2E.FakeEncoder encoder, LoginLockoutPolicy lockout, Clock clock) {
            return new PasswordChangeService(identity, transactions, accounts, sessions, encoder, lockout, clock);
        }
        @Bean
        @Scope("operation")
        Probe passwordProbe(Transactions transactions, AccountStore accounts, SessionLifecycleStore sessions,
                IamAuthenticationE2E.FakeEncoder encoder, Clock clock) {
            return new Probe(transactions, accounts, sessions, encoder, clock);
        }
    }
}
