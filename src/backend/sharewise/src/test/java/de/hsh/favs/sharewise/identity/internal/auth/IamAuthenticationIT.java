package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IAM workflows against production PostgreSQL repositories in the shared rollback fixture.
 * Only single-segment behavior belongs here; durable and multi-segment login cases remain E2E.
 */
@Tag("integration")
class IamAuthenticationIT extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PASSWORD = "long-enough-password";
    private static final CredentialEncoder TEST_ENCODER = new TestEncoder();
    private static final AccessTokenIssuer TEST_ISSUER = (userId, sessionId, issuedAt, expiresAt) ->
            new IssuedAccessToken("test-only-token:" + sessionId, expiresAt);

    @Test
    void registrationWritesPendingAccountSessionAndRefreshVerifierWithoutCommitting() throws SQLException {
        AtomicReference<UUID> createdUserId = new AtomicReference<>();
        runIntegrationTest(scope -> {
            AuthenticationResult registered = successful(registrationService(scope, TEST_ISSUER)
                    .register(registration("  User@Example.org  ")));
            createdUserId.set(registered.userId());

            assertEquals(TransactionDecision.COMMIT, scope.observation().requestedDecision());
            assertNull(scope.observation().failure());
            assertEquals(7, registered.userId().version());
            assertEquals(7, registered.sessionId().version());
            assertEquals(7, registered.refreshCredential().tokenId().version());
            assertEquals(NOW.plusSeconds(300), registered.accessToken().expiresAt());

            scope.inspect(inspector -> {
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.users where id = ?", Integer.class, registered.userId()));
                assertEquals("User@Example.org", inspector.queryForObject(
                        "select email from identity.users where id = ?", String.class, registered.userId()));
                assertEquals("user@example.org", inspector.queryForObject(
                        "select normalized_email from identity.users where id = ?", String.class,
                        registered.userId()));
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.sessions where id = ? and user_id = ?",
                        Integer.class, registered.sessionId(), registered.userId()));
                assertEquals(NOW.plusSeconds(604800), inspector.queryForObject(
                        "select idle_expires_at from identity.sessions where id = ?",
                        Timestamp.class, registered.sessionId()).toInstant());
                assertEquals(NOW.plusSeconds(2592000), inspector.queryForObject(
                        "select absolute_expires_at from identity.sessions where id = ?",
                        Timestamp.class, registered.sessionId()).toInstant());
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.refresh_tokens where id = ? and session_id = ? "
                                + "and consumed_at is null", Integer.class,
                        registered.refreshCredential().tokenId(), registered.sessionId()));
                assertEquals(HexFormat.of().formatHex(registered.refreshCredential().verifierHash()),
                        inspector.queryForObject("select encode(verifier_hash, 'hex') "
                                + "from identity.refresh_tokens where id = ?", String.class,
                                registered.refreshCredential().tokenId()));
                return null;
            });
            assertEquals(0, independentAccountCount(registered.userId()));
        });
        // The harness rolls back its outer physical transaction despite the logical COMMIT.
        assertEquals(0, independentAccountCount(createdUserId.get()));
    }

    @Test
    void signerFailureLeavesOnlyRollbackOwnedPendingRows() throws SQLException {
        runIntegrationTest(scope -> {
            IllegalStateException expected = new IllegalStateException("test-only signing failure");
            AccessTokenIssuer failing = (userId, sessionId, issuedAt, expiresAt) -> {
                throw expected;
            };
            assertSame(expected, assertThrows(IllegalStateException.class,
                    () -> registrationService(scope, failing).register(registration("failure-it@example.org"))));
            assertSame(expected, scope.observation().failure());
            assertNull(scope.observation().requestedDecision());
            // These writes happened, but the service never returned a credential and no commit occurred.
            scope.inspect(inspector -> {
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.users where normalized_email = ?",
                        Integer.class, "failure-it@example.org"));
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.sessions", Integer.class));
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.refresh_tokens", Integer.class));
                return null;
            });
            assertEquals(0, independentAccountCount("failure-it@example.org"));
        });
        assertEquals(0, independentAccountCount("failure-it@example.org"));
    }

    @Test
    void mismatchedIssuerExpirationFailsClosedWithinTheLogicalSegment() throws SQLException {
        runIntegrationTest(scope -> {
            AccessTokenIssuer invalid = (userId, sessionId, issuedAt, expiresAt) ->
                    new IssuedAccessToken("test-only-token", expiresAt.plusSeconds(1));
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> registrationService(scope, invalid).register(registration("expiry-it@example.org")));
            assertTrue(rejected.getMessage().contains("expiration"));
            assertSame(rejected, scope.observation().failure());
            assertNull(scope.observation().requestedDecision());
            assertEquals(0, independentAccountCount("expiry-it@example.org"));
        });
        assertEquals(0, independentAccountCount("expiry-it@example.org"));
    }

    @Test
    void accountAdapterCanonicalizesUniqueEmailAndReadsItsOwnUncommittedInsert() throws SQLException {
        runIntegrationTest(scope -> {
            AccountStore accounts = scope.resolve(AccountStore.class);
            UUID id = scope.resolve(Transactions.class).execute(() -> {
                AccountSnapshot first = accounts.insert(EmailAddress.parse("  Case.IT@Example.org  "),
                        TEST_ENCODER.encode(PASSWORD)).orElseThrow();
                assertEquals(Optional.empty(), accounts.insert(EmailAddress.parse("case.it@EXAMPLE.org"),
                        TEST_ENCODER.encode(PASSWORD)));
                assertEquals(first, accounts.findByEmail(EmailAddress.parse("CASE.IT@example.org"))
                        .orElseThrow());
                return TransactionOutcome.commit(first.userId());
            });
            assertEquals(TransactionDecision.COMMIT, scope.observation().requestedDecision());
            scope.inspect(inspector -> {
                assertEquals(1, inspector.queryForObject(
                        "select count(*) from identity.users where normalized_email = ?",
                        Integer.class, "case.it@example.org"));
                assertEquals(0, inspector.queryForObject(
                        "select count(*) from identity.sessions where user_id = ?", Integer.class, id));
                return null;
            });
            assertEquals(0, independentAccountCount(id));
        });
        assertEquals(0, independentAccountCount("case.it@example.org"));
    }

    @Test
    void accountAdapterRejectsStaleLoginFailureUpdatesAfterLockedChange() throws SQLException {
        runIntegrationTest(scope -> {
            AccountStore accounts = scope.resolve(AccountStore.class);
            LoginLockoutPolicy policy = LoginLockoutPolicy.initial();
            UUID id = scope.resolve(Transactions.class).execute(() -> {
                EmailAddress email = EmailAddress.parse("counter-it@example.org");
                AccountSnapshot inserted = accounts.insert(email, TEST_ENCODER.encode(PASSWORD))
                        .orElseThrow();
                AccountSnapshot locked = accounts.findByEmailForUpdate(email).orElseThrow();
                LoginLockoutPolicy.State old = locked.loginFailures();
                LoginLockoutPolicy.State next = policy.failure(old, NOW);
                assertTrue(accounts.compareAndSetLoginFailures(locked.userId(), old, next));
                assertFalse(accounts.compareAndSetLoginFailures(locked.userId(), old,
                        policy.failure(next, NOW)));
                assertEquals(next, accounts.findByEmail(email).orElseThrow().loginFailures());
                return TransactionOutcome.commit(inserted.userId());
            });
            assertEquals(TransactionDecision.COMMIT, scope.observation().requestedDecision());
            scope.inspect(inspector -> {
                assertEquals(1, inspector.queryForObject(
                        "select failed_login_attempts from identity.users where id = ?",
                        Integer.class, id));
                assertNull(inspector.queryForObject(
                        "select locked_until from identity.users where id = ?",
                        Timestamp.class, id));
                return null;
            });
            assertEquals(0, independentAccountCount(id));
        });
        assertEquals(0, independentAccountCount("counter-it@example.org"));
    }

    @Test
    void unknownAccountLoginUsesDummyVerifierAndAbstainsWithoutWriting() {
        AtomicInteger verifications = new AtomicInteger();
        AtomicReference<String> checkedVerifier = new AtomicReference<>();
        CredentialEncoder observing = new CredentialEncoder() {
            @Override public String encode(String password) { return TEST_ENCODER.encode(password); }
            @Override public boolean matches(String raw, String encoded) {
                verifications.incrementAndGet();
                checkedVerifier.set(encoded);
                return false;
            }
        };
        runIntegrationTest(scope -> {
            LoginService service = new LoginService(scope.resolve(Transactions.class),
                    scope.resolve(AccountStore.class), observing,
                    initialAuthentication(scope, TEST_ISSUER), LoginLockoutPolicy.initial(),
                    CLOCK, "test-only-dummy-verifier");
            Result<AuthenticationResult, LoginFailure> rejected = service.login(LoginCommand.from(
                    "absent-it@example.org", PASSWORD, PasswordPolicy.initial()));
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rejected);
            assertEquals(TransactionDecision.ABSTAIN, scope.observation().requestedDecision());
            assertEquals(1, verifications.get());
            assertEquals("test-only-dummy-verifier", checkedVerifier.get());
            scope.inspect(inspector -> {
                assertEquals(0, inspector.queryForObject(
                        "select count(*) from identity.users where normalized_email = ?",
                        Integer.class, "absent-it@example.org"));
                return null;
            });
        });
    }

    private static RegistrationCommand registration(String email) {
        return RegistrationCommand.from(email, PASSWORD, PasswordPolicy.initial());
    }

    private static RegistrationService registrationService(IntegrationTestScope scope, AccessTokenIssuer issuer) {
        // Phase C has no production service wiring yet. Compose the real Spring-managed
        // operation-scoped stores and transaction manager with explicit test-only crypto.
        return new RegistrationService(scope.resolve(Transactions.class), scope.resolve(AccountStore.class),
                TEST_ENCODER, initialAuthentication(scope, issuer), CLOCK);
    }

    private static InitialAuthentication initialAuthentication(IntegrationTestScope scope, AccessTokenIssuer issuer) {
        return new InitialAuthentication(scope.resolve(SessionStore.class),
                scope.resolve(RefreshTokenStore.class), new RefreshCredentialGenerator(),
                SessionPolicy.initial(), issuer);
    }

    private static AuthenticationResult successful(Result<AuthenticationResult, RegistrationFailure> result) {
        assertTrue(result instanceof Result.Ok<AuthenticationResult, RegistrationFailure>);
        return ((Result.Ok<AuthenticationResult, RegistrationFailure>) result).value();
    }

    private int independentAccountCount(UUID id) throws SQLException {
        return queryCommittedAccountCount("select count(*) from identity.users where id = ?", id);
    }

    private int independentAccountCount(String email) throws SQLException {
        return queryCommittedAccountCount("select count(*) from identity.users where normalized_email = ?", email);
    }

    private int queryCommittedAccountCount(String query, Object value) throws SQLException {
        try (Connection connection = fixture().connectAsApplication();
             PreparedStatement sql = connection.prepareStatement(query)) {
            sql.setObject(1, value);
            try (ResultSet result = sql.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static final class TestEncoder implements CredentialEncoder {
        @Override
        public String encode(String password) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(password.getBytes(StandardCharsets.UTF_8));
                return "test-only-sha256:" + HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException failure) {
                throw new AssertionError("SHA-256 provider unavailable", failure);
            }
        }

        @Override
        public boolean matches(String raw, String encoded) {
            return encode(raw).equals(encoded);
        }
    }
}
