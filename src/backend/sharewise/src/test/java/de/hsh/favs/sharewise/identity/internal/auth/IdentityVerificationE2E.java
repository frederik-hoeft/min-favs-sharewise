package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.identity.IdentityPort;
import de.hsh.favs.sharewise.identity.IdentityVerificationFailure;
import de.hsh.favs.sharewise.identity.VerifiedIdentity;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.execution.AdjustableClock;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/** Real IAM wiring, admitted JWT evidence, independent connections and physical transaction completion. */
@Tag("e2e")
class IdentityVerificationE2E {
    private static final String EMAIL = "identity-port@example.org";
    private static final String PASSWORD = "original-password-long";
    @TempDir Path keysDirectory;

    @Test
    void committedLifecycleChangesInvalidateStaleEvidenceButRetainPasswordChangeCaller() throws Exception {
        runWithProductionIam(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            AuthenticationResult first = register(dispatcher, EMAIL);
            AuthenticationResult second = login(dispatcher);
            AuthenticationResult other = register(dispatcher, "other@example.org");
            String firstToken = first.accessToken().value();
            String secondToken = second.accessToken().value();
            String otherToken = other.accessToken().value();
            String before = snapshot(fixture);
            assertEligible(fixture, first, firstToken);
            assertEligible(fixture, second, secondToken);
            assertEquals(before, snapshot(fixture), "verification must not modify account, session or refresh history");
            // Use the real transaction coordinator and published port in the same operation.
            dispatch(fixture, firstToken, entry -> {
                assertEquals("read", entry.transactions().execute(() -> TransactionOutcome.abstain("read")));
                assertTrue(entry.port().verifyCurrent().isSuccess());
                assertThrows(IllegalStateException.class, () -> entry.transactions().execute(() -> {
                    entry.port().verifyCurrent();
                    return TransactionOutcome.commit("must not commit");
                }));
                assertTrue(entry.port().verifyCurrent().isSuccess());
                assertEquals("write segment", entry.transactions().execute(() ->
                        TransactionOutcome.commit("write segment")));
                return null;
            });

            ChangePasswordCommand change = ChangePasswordCommand.tryFrom(PASSWORD, "replacement-password-long",
                    PasswordPolicy.initial()).fold(command -> command, errors -> { throw new AssertionError(errors); });
            assertEquals(Result.ok(PasswordChangeStatus.CHANGED), dispatcher.dispatch(
                    inputs -> inputs.add(VerifiedJwtCredential.class, evidence(fixture, firstToken)),
                    PasswordChangeService.class, service -> service.change(change)));
            assertEligible(fixture, first, firstToken);
            assertDenied(fixture, secondToken);
            assertEligible(fixture, other, otherToken);

            assertEquals(Result.ok(LogoutStatus.REVOKED), dispatcher.dispatch(SessionLifecycleService.class,
                    service -> service.logout(first.refreshCredential().cookieValue(), first.sessionId())));
            assertDenied(fixture, firstToken);

            // Account disablement is not yet a public IAM operation. Use a committed independent
            // connection to exercise its authoritative persistence semantics without inventing an API.
            try (Connection writer = fixture.connectAsApplication()) {
                writer.setAutoCommit(false);
                try (PreparedStatement update = writer.prepareStatement(
                        "update identity.users set disabled_at = now() where id = ?")) {
                    update.setObject(1, other.userId());
                    assertEquals(1, update.executeUpdate());
                }
                // A plain snapshot read must neither observe this uncommitted change nor wait on its row lock.
                FutureTask<Result<VerifiedIdentity, IdentityVerificationFailure>> concurrent =
                        new FutureTask<>(() -> verify(fixture, otherToken));
                Thread.ofVirtual().start(concurrent);
                assertTrue(concurrent.get(10, TimeUnit.SECONDS).isSuccess());
                writer.commit();
            }
            assertDenied(fixture, otherToken);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sessionExpiringWhileAuthoritativeReadIsBlockedIsDenied(boolean absoluteDeadline) throws Exception {
        runWithProductionIam(fixture -> {
            AdjustableClock clock = fixture.context().getBean(AdjustableClock.class);
            AuthenticationResult caller = register(fixture.context().getBean(OperationDispatcher.class), EMAIL);
            String token = caller.accessToken().value();
            Instant expiry = clock.instant().plusSeconds(2);
            // Shorten the persisted idle deadline, or both deadlines for an absolute cap.
            // The already issued JWT is still valid when the clock reaches this deadline.
            try (Connection writer = fixture.connectAsApplication(); PreparedStatement update = writer.prepareStatement(
                    "update identity.sessions set idle_expires_at = ?, absolute_expires_at = ? where id = ?")) {
                update.setObject(1, expiry.atOffset(ZoneOffset.UTC));
                update.setObject(2, (absoluteDeadline ? expiry : expiry.plusSeconds(1)).atOffset(ZoneOffset.UTC));
                update.setObject(3, caller.sessionId());
                assertEquals(1, update.executeUpdate());
            }
            clock.setInstant(expiry.minusNanos(1));
            assertEligible(fixture, caller, token);
            String before = snapshot(fixture);
            try (Connection blocker = fixture.connectAsApplication(); Statement lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                lock.execute("lock table identity.sessions in access exclusive mode");
                FutureTask<Result<VerifiedIdentity, IdentityVerificationFailure>> call =
                        new FutureTask<>(() -> verify(fixture, token));
                Thread.ofVirtual().start(call);
                awaitBlockedQuery(fixture);
                clock.setInstant(expiry);
                blocker.rollback();
                assertEquals(Result.err(IdentityVerificationFailure.INELIGIBLE), call.get(10, TimeUnit.SECONDS));
            }
            assertDenied(fixture, token);
            assertEquals(before, snapshot(fixture), "verification must not mutate session state");
        });
    }

    @Test
    void canceledLookupReturnsUnavailableAfterRollbackAndAllowsLaterIndependentCheck() throws Exception {
        runWithProductionIam(fixture -> {
            AuthenticationResult caller = register(fixture.context().getBean(OperationDispatcher.class), EMAIL);
            String token = caller.accessToken().value();
            String before = snapshot(fixture);
            try (Connection blocker = fixture.connectAsApplication(); Statement lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                lock.execute("lock table identity.sessions in access exclusive mode");
                FutureTask<Result<VerifiedIdentity, IdentityVerificationFailure>> call = new FutureTask<>(() ->
                        dispatch(fixture, token, entry -> {
                            Result<VerifiedIdentity, IdentityVerificationFailure> result = entry.port().verifyCurrent();
                            assertEquals(Result.err(IdentityVerificationFailure.UNAVAILABLE), result);
                            assertEquals("next segment", entry.transactions().execute(() ->
                                    TransactionOutcome.abstain("next segment")));
                            return result;
                        }));
                Thread.ofVirtual().start(call);
                int pid = awaitBlockedQuery(fixture);
                signal(fixture, "select pg_cancel_backend(?)", pid);
                assertEquals(Result.err(IdentityVerificationFailure.UNAVAILABLE), call.get(10, TimeUnit.SECONDS));
                blocker.rollback();
            }
            assertEligible(fixture, caller, token);
            assertEquals(before, snapshot(fixture));
        });
    }

    @Test
    void terminatedLookupCannotAuthorizeOrReuseIndeterminateTransaction() throws Exception {
        runWithProductionIam(fixture -> {
            AuthenticationResult caller = register(fixture.context().getBean(OperationDispatcher.class), EMAIL);
            String token = caller.accessToken().value();
            String before = snapshot(fixture);
            try (Connection blocker = fixture.connectAsApplication(); Statement lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                lock.execute("lock table identity.sessions in access exclusive mode");
                FutureTask<Boolean> call = new FutureTask<>(() -> dispatch(fixture, token, entry -> {
                    assertThrows(RuntimeException.class, entry.port()::verifyCurrent);
                    assertThrows(IllegalStateException.class, () -> entry.transactions().execute(() ->
                            TransactionOutcome.commit("must not commit")));
                    return true;
                }));
                Thread.ofVirtual().start(call);
                signal(fixture, "select pg_terminate_backend(?)", awaitBlockedQuery(fixture));
                assertTrue(call.get(10, TimeUnit.SECONDS));
                blocker.rollback();
            }
            assertEquals(before, snapshot(fixture));
        });
    }

    private void runWithProductionIam(TestCallbacks.Action<IntegrationTestContext> action) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Path publicKey = keysDirectory.resolve("public.pem");
        Path privateKey = keysDirectory.resolve("private.pem");
        writePem(publicKey, "PUBLIC KEY", pair.getPublic().getEncoded());
        writePem(privateKey, "PRIVATE KEY", pair.getPrivate().getEncoded());
        Map<String, Object> settings = Map.of(
                "sharewise.iam.auth.enabled", "true",
                "sharewise.iam.auth.issuer", "https://iam.sharewise.test",
                "sharewise.iam.auth.audience", "sharewise-api",
                "sharewise.iam.auth.active-key-id", "e2e",
                "sharewise.iam.auth.keys[0].id", "e2e",
                "sharewise.iam.auth.keys[0].public-key-file", publicKey.toString(),
                "sharewise.iam.auth.keys[0].private-key-file", privateKey.toString());
        try (IntegrationTestContext fixture = IntegrationTestContext.startServlet(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> { }, settings,
                ShareWiseApplication.class, ProductionEntityScanConfiguration.class, Fixtures.class)) {
            action.accept(fixture);
        }
    }

    private static void writePem(Path path, String type, byte[] bytes) throws Exception {
        Files.writeString(path, "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n");
    }

    private static AuthenticationResult register(OperationDispatcher dispatcher, String email) {
        return dispatcher.dispatch(RegistrationService.class, service -> service.register(
                RegistrationCommand.from(email, PASSWORD, PasswordPolicy.initial())))
                .fold(value -> value, failure -> { throw new AssertionError(failure); });
    }

    private static AuthenticationResult login(OperationDispatcher dispatcher) {
        return dispatcher.dispatch(LoginService.class, service -> service.login(
                LoginCommand.from(EMAIL, PASSWORD, PasswordPolicy.initial())))
                .fold(value -> value, failure -> { throw new AssertionError(failure); });
    }

    private static VerifiedJwtCredential evidence(IntegrationTestContext fixture, String token) {
        return fixture.context().getBean("iamPrimaryJwtCredentialCodec", JwtCredentialCodec.class)
                .verifyPrimary(token);
    }

    private static <T> T dispatch(IntegrationTestContext fixture, String token,
            java.util.function.Function<Entry, T> call) {
        return fixture.context().getBean(OperationDispatcher.class).dispatch(
                inputs -> inputs.add(VerifiedJwtCredential.class, evidence(fixture, token)), Entry.class, call);
    }

    private static Result<VerifiedIdentity, IdentityVerificationFailure> verify(
            IntegrationTestContext fixture, String token) {
        return dispatch(fixture, token, entry -> entry.port().verifyCurrent());
    }

    private static void assertEligible(IntegrationTestContext fixture, AuthenticationResult caller,
            String token) {
        AdjustableClock clock = fixture.context().getBean(AdjustableClock.class);
        Instant before = clock.instant();
        VerifiedIdentity identity = verify(fixture, token).fold(value -> value,
                failure -> { throw new AssertionError(failure); });
        assertEquals(caller.userId(), identity.userId());
        assertEquals(caller.sessionId(), identity.sessionId());
        assertFalse(identity.checkedAt().isBefore(before));
        assertFalse(identity.checkedAt().isAfter(clock.instant()));
    }

    private static void assertDenied(IntegrationTestContext fixture, String token) {
        assertEquals(Result.err(IdentityVerificationFailure.INELIGIBLE), verify(fixture, token));
    }

    private static String snapshot(IntegrationTestContext fixture) throws Exception {
        try (Connection connection = fixture.connectAsApplication(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select md5(jsonb_build_array(
                       (select jsonb_agg(to_jsonb(u) order by id) from identity.users u),
                       (select jsonb_agg(to_jsonb(s) order by id) from identity.sessions s),
                       (select jsonb_agg(to_jsonb(r) order by id) from identity.refresh_tokens r))::text)
                     """)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static int awaitBlockedQuery(IntegrationTestContext fixture) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = fixture.connectAsApplication(); PreparedStatement query = observer.prepareStatement("""
                select pid from pg_stat_activity where datname = current_database()
                  and pid <> pg_backend_pid() and wait_event_type = 'Lock'
                  and query like '%sessions%' and query like '%users%' and query like '%idle_expires_at%'
                """)) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        int pid = rows.getInt(1);
                        assertFalse(rows.next(), "expected exactly one blocked eligibility query");
                        return pid;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("Eligibility query did not reach the PostgreSQL lock wait");
    }

    private static void signal(IntegrationTestContext fixture, String sql, int pid) throws Exception {
        try (Connection connection = fixture.connectAsApplication(); PreparedStatement signal = connection.prepareStatement(sql)) {
            signal.setInt(1, pid);
            try (ResultSet rows = signal.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
            }
        }
    }

    record Entry(IdentityPort port, Transactions transactions) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary @Qualifier("iamClock")
        AdjustableClock testIamClock() {
            return new AdjustableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }

        @Bean @Scope("operation")
        Entry verificationEntry(IdentityPort port, Transactions transactions) {
            return new Entry(port, transactions);
        }
    }
}
