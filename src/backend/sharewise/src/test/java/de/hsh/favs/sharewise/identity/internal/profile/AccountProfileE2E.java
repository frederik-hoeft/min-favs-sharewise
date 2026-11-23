package de.hsh.favs.sharewise.identity.internal.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** PostgreSQL profile projection, atomic upsert, ownership, and rollback across committed segments. */
@Tag("e2e")
class AccountProfileE2E extends E2ETestBase {

    @Test
    void optionalProfileCanBeSetAndClearedWithoutAffectingOtherUsers() {
        runTest(fixture -> {
            UUID alice;
            UUID bob;
            try (Connection app = fixture.connectAsApplication()) {
                alice = insertUser(app, "Alice@Example.org");
                bob = insertUser(app, "bob@example.org");
            }
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", null)),
                    get(dispatcher, alice));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "🌍 Alice")),
                    update(dispatcher, alice, "🌍 Alice"));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "🌍 Alice")),
                    get(dispatcher, alice));
            assertEquals(Result.ok(new AccountProfile(bob, "bob@example.org", null)),
                    get(dispatcher, bob));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", null)),
                    update(dispatcher, alice, null));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", null)),
                    update(dispatcher, alice, null));
            try (Connection app = fixture.connectAsApplication()) {
                assertEquals(1, countProfiles(app, alice));
                assertEquals(0, countProfiles(app, bob));
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void missingAndDisabledAccountsCannotBeReadOrUpdated() {
        runTest(fixture -> {
            UUID alice;
            try (Connection app = fixture.connectAsApplication()) {
                alice = insertUser(app, "disabled@example.org");
            }
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            UUID missing = UUID.randomUUID();
            assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE), get(dispatcher, missing));
            assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE),
                    update(dispatcher, missing, "Rejected"));
            try (Connection app = fixture.connectAsApplication();
                 PreparedStatement disable = app.prepareStatement(
                         "update identity.users set disabled_at = now() where id = ?")) {
                disable.setObject(1, alice);
                assertEquals(1, disable.executeUpdate());
            }
            assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE), get(dispatcher, alice));
            assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE),
                    update(dispatcher, alice, "Rejected"));
            try (Connection app = fixture.connectAsApplication()) {
                assertEquals(0, countProfiles(app, alice));
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void simultaneousFirstWritesProduceOneProfileAndRollbackDoesNotPersist() {
        runTest(fixture -> {
            UUID alice;
            try (Connection app = fixture.connectAsApplication()) {
                alice = insertUser(app, "concurrent@example.org");
            }
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Result<AccountProfile, AccountProfileFailure>> first = new FutureTask<>(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return update(dispatcher, alice, "First");
            });
            FutureTask<Result<AccountProfile, AccountProfileFailure>> second = new FutureTask<>(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return update(dispatcher, alice, "Second");
            });
            Thread.ofVirtual().start(first);
            Thread.ofVirtual().start(second);
            start.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS).isSuccess());
            assertTrue(second.get(15, TimeUnit.SECONDS).isSuccess());
            try (Connection app = fixture.connectAsApplication()) {
                assertEquals(1, countProfiles(app, alice));
            }
            String persisted = ((Result.Ok<AccountProfile, AccountProfileFailure>) get(dispatcher, alice))
                    .value().displayName();
            assertTrue("First".equals(persisted) || "Second".equals(persisted));

            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(Probe.class,
                    probe -> probe.transactions().execute(() -> {
                        assertTrue(probe.profiles().setDisplayNameIfActive(alice, "Must roll back").isPresent());
                        throw new IllegalStateException("induced rollback");
                    })));
            assertEquals(persisted, ((Result.Ok<AccountProfile, AccountProfileFailure>) get(dispatcher, alice))
                    .value().displayName());
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    private static Result<AccountProfile, AccountProfileFailure> get(OperationDispatcher dispatcher, UUID userId) {
        return dispatcher.dispatch(Probe.class, probe ->
                service(userId, probe).get());
    }

    private static Result<AccountProfile, AccountProfileFailure> update(OperationDispatcher dispatcher, UUID userId,
            String displayName) {
        DisplayNameUpdate command = ((Result.Ok<DisplayNameUpdate, ?>) DisplayNameUpdate.tryFrom(displayName))
                .value();
        return dispatcher.dispatch(Probe.class, probe -> service(userId, probe).setDisplayName(command));
    }

    private static AccountProfileService service(UUID userId, Probe probe) {
        IdentityContext identity = new IdentityContext() {
            @Override public Optional<UUID> userId() { return Optional.of(userId); }
            @Override public Optional<String> sessionId() { return Optional.of(userId.toString()); }
        };
        return new AccountProfileService(identity, probe.transactions(), probe.profiles());
    }

    private static UUID insertUser(Connection connection, String email) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into identity.users(email, normalized_email, password_hash)
                values (?, lower(?), 'encoded') returning id
                """)) {
            insert.setString(1, email);
            insert.setString(2, email);
            try (ResultSet rows = insert.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static int countProfiles(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select count(*) from identity.user_profiles where user_id = ?")) {
            query.setObject(1, userId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    record Probe(Transactions transactions, AccountProfileStore profiles) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Scope("operation")
        Probe profileProbe(Transactions transactions, AccountProfileStore profiles) {
            return new Probe(transactions, profiles);
        }
    }
}
