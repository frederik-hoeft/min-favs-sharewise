package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** Physical PostgreSQL acceptance for the IAM workflows, with explicit test-only crypto fixtures. */
@Tag("e2e")
class IamAuthenticationE2E extends E2ETestBase {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final String PASSWORD = "long-enough-password";

    @Test
    void registrationAndLoginCreateCommittedIndependentSessionsAndResetFailureCounter() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult registered = success(dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@Example.org"))));
            assertCount(fixture.connectAsApplication(), "identity.users", 1);
            assertCount(fixture.connectAsApplication(), "identity.user_profiles", 1);
            assertCount(fixture.connectAsApplication(), "identity.sessions", 1);
            assertCount(fixture.connectAsApplication(), "identity.refresh_tokens", 1);
            for (int i = 0; i < 2; i++) {
                assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS),
                        dispatcher.dispatch(LoginService.class,
                                service -> service.login(login("bad-password"))));
            }
            assertFailures(fixture.connectAsApplication(), 2, false);
            AuthenticationResult loggedIn = success(dispatcher.dispatch(LoginService.class,
                    service -> service.login(login(PASSWORD))));
            assertNotEquals(registered.sessionId(), loggedIn.sessionId());
            assertEquals(registered.userId(), loggedIn.userId());
            assertCount(fixture.connectAsApplication(), "identity.sessions", 2);
            assertCount(fixture.connectAsApplication(), "identity.refresh_tokens", 2);
            assertFailures(fixture.connectAsApplication(), 0, false);
            try (Connection connection = fixture.connectAsApplication();
                 PreparedStatement sql = connection.prepareStatement("""
                         select count(*) from identity.refresh_tokens r
                         join identity.sessions s on r.session_id = s.id
                         where r.consumed_at is null and s.user_id = ?
                         """)) {
                sql.setObject(1, loggedIn.userId());
                try (ResultSet rows = sql.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void duplicateRegistrationAndSignerFailureNeverLeavePartialPersistentState() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            FakeIssuer issuer = fixture.context().getBean(FakeIssuer.class);
            issuer.fail.set(true);
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@example.org"))));
            assertCount(fixture.connectAsApplication(), "identity.users", 0);
            assertCount(fixture.connectAsApplication(), "identity.user_profiles", 0);
            assertCount(fixture.connectAsApplication(), "identity.sessions", 0);
            assertCount(fixture.connectAsApplication(), "identity.refresh_tokens", 0);
            issuer.fail.set(false);
            assertTrue(dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@example.org"))).isSuccess());
            assertEquals(Result.err(RegistrationFailure.EMAIL_UNAVAILABLE),
                    dispatcher.dispatch(RegistrationService.class,
                            service -> service.register(registration("  USER@EXAMPLE.ORG  "))));
            assertCount(fixture.connectAsApplication(), "identity.users", 1);
            assertCount(fixture.connectAsApplication(), "identity.user_profiles", 1);
            assertCount(fixture.connectAsApplication(), "identity.sessions", 1);
            assertCount(fixture.connectAsApplication(), "identity.refresh_tokens", 1);
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void failedProfileInsertRollsBackAccountAndAuthentication() {
        runTest(fixture -> {
            try (Connection migrator = fixture.database().connectAsMigrator();
                 java.sql.Statement sql = migrator.createStatement()) {
                sql.execute("alter table identity.user_profiles add constraint test_profile_rejection check (display_name <> 'Test user')");
            }
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            assertThrows(RuntimeException.class, () -> dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@example.org"))));
            for (String table : java.util.List.of("identity.users", "identity.user_profiles", "identity.sessions", "identity.refresh_tokens")) {
                assertCount(fixture.connectAsApplication(), table, 0);
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void failureThresholdLocksAndCorrectPasswordCannotBypassActiveLock() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@example.org")));
            for (int i = 0; i < 5; i++) {
                assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS),
                        dispatcher.dispatch(LoginService.class, service -> service.login(login("wrong-password"))));
            }
            assertFailures(fixture.connectAsApplication(), 5, true);
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), dispatcher.dispatch(
                    LoginService.class, service -> service.login(login(PASSWORD))));
            assertCount(fixture.connectAsApplication(), "identity.sessions", 1);
            assertFailures(fixture.connectAsApplication(), 5, true);
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void concurrentIncorrectLoginsCannotLoseFailureCountersAtTheThreshold() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@example.org")));
            for (int i = 0; i < 3; i++) {
                dispatcher.dispatch(LoginService.class, service -> service.login(login("wrong-password")));
            }
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<AuthenticationResult, LoginFailure>> first =
                    new FutureTask<>(() -> concurrentLogin(dispatcher, start));
            FutureTask<Result<AuthenticationResult, LoginFailure>> second =
                    new FutureTask<>(() -> concurrentLogin(dispatcher, start));
            Thread.ofVirtual().start(first);
            Thread.ofVirtual().start(second);
            start.countDown();
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), first.get(15, TimeUnit.SECONDS));
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), second.get(15, TimeUnit.SECONDS));
            assertFailures(fixture.connectAsApplication(), 5, true);
            assertCount(fixture.connectAsApplication(), "identity.sessions", 1);
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    private static Result<AuthenticationResult, LoginFailure> concurrentLogin(
            OperationDispatcher dispatcher, CountDownLatch start) throws InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent login start timed out");
        }
        return dispatcher.dispatch(LoginService.class, service -> service.login(login("wrong-password")));
    }

    @Test
    void changedPasswordBetweenSnapshotAndRowLockRejectsStaleVerification() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            dispatcher.dispatch(RegistrationService.class,
                    service -> service.register(registration("User@example.org")));
            FakeEncoder encoder = fixture.context().getBean(FakeEncoder.class);
            encoder.onVerify = () -> {
                try (Connection connection = fixture.connectAsApplication();
                     PreparedStatement sql = connection.prepareStatement(
                             "update identity.users set password_hash = ? where normalized_email = ?")) {
                    sql.setString(1, encoder.encode("a-different-password"));
                    sql.setString(2, "user@example.org");
                    assertEquals(1, sql.executeUpdate());
                } catch (SQLException failure) {
                    throw new IllegalStateException("Failed to simulate competing password update", failure);
                }
            };
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), dispatcher.dispatch(
                    LoginService.class, service -> service.login(login(PASSWORD))));
            assertCount(fixture.connectAsApplication(), "identity.sessions", 1);
            assertFailures(fixture.connectAsApplication(), 0, false);
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    private static RegistrationCommand registration(String email) {
        return RegistrationCommand.from(email, PASSWORD, "Test user", PasswordPolicy.initial());
    }

    private static LoginCommand login(String password) {
        return LoginCommand.from("user@example.org", password, PasswordPolicy.initial());
    }

    private static <E> AuthenticationResult success(Result<AuthenticationResult, E> result) {
        return ((Result.Ok<AuthenticationResult, E>) result).value();
    }

    private static void assertCount(Connection connection, String table, int expected) throws SQLException {
        try (connection; PreparedStatement sql = connection.prepareStatement(
                "select count(*) from " + table); ResultSet rows = sql.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1));
            assertFalse(rows.next());
        }
    }

    private static void assertFailures(Connection connection, int attempts, boolean locked) throws SQLException {
        try (connection; PreparedStatement sql = connection.prepareStatement(
                "select failed_login_attempts, locked_until from identity.users");
             ResultSet rows = sql.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(attempts, rows.getInt(1));
            if (locked) {
                assertEquals(NOW.plusSeconds(900), rows.getTimestamp(2).toInstant());
            } else {
                assertEquals(null, rows.getTimestamp(2));
            }
            assertFalse(rows.next());
        }
    }

    /** No test-only encoder or issuer is scanned without explicit import of this fixture. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean FakeEncoder fakeEncoder() { return new FakeEncoder(); }
        @Bean FakeIssuer fakeIssuer() { return new FakeIssuer(); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SessionPolicy sessionPolicy() { return SessionPolicy.initial(); }
        @Bean LoginLockoutPolicy loginLockoutPolicy() { return LoginLockoutPolicy.initial(); }
        @Bean @Scope("operation")
        InitialAuthentication initialAuthentication(SessionStore sessions, RefreshTokenStore tokens,
                ApplicationTokenGenerator generator, SessionPolicy policy, FakeIssuer issuer) {
            return new InitialAuthentication(sessions, tokens, generator, policy, issuer);
        }
        @Bean UserEventPublisher userEventPublisher(ApplicationEventPublisher events) {
            return new SpringUserEventPublisher(events);
        }
        @Bean @Scope("operation")
        RegistrationService registrationService(Transactions transactions, AccountStore accounts,
                FakeEncoder encoder, InitialAuthentication authentication, UserEventPublisher events,
                Clock clock) {
            return new RegistrationService(transactions, accounts, encoder, authentication, events, clock);
        }
        @Bean @Scope("operation")
        LoginService loginService(Transactions transactions, AccountStore accounts,
                FakeEncoder encoder, InitialAuthentication authentication,
                LoginLockoutPolicy lockouts, Clock clock) {
            return new LoginService(transactions, accounts, encoder, authentication, lockouts,
                    clock, encoder.encode("test-only-dummy-password"));
        }
    }

    /** Deliberately not an adaptive password hash: test-only verification seam. */
    static final class FakeEncoder implements CredentialEncoder {
        Runnable onVerify;
        @Override public String encode(String password) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                        password.getBytes(StandardCharsets.UTF_8));
                return "test-sha256:" + HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException failure) {
                throw new AssertionError("SHA-256 unavailable", failure);
            }
        }
        @Override public boolean matches(String raw, String hash) {
            if (onVerify != null) {
                onVerify.run();
            }
            return encode(raw).equals(hash);
        }
    }

    static final class FakeIssuer implements AccessTokenIssuer {
        final AtomicBoolean fail = new AtomicBoolean();
        @Override public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt,
                Instant expiresAt) {
            if (fail.get()) {
                throw new IllegalStateException("Simulated signing failure");
            }
            return new IssuedAccessToken("test-only-token:" + sessionId, expiresAt);
        }
    }
}
