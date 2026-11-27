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

/** PostgreSQL profile projection, serialized update, ownership, and rollback across committed segments. */
@Tag("e2e")
class AccountProfileE2E extends E2ETestBase {

    @Test
    void requiredProfileCanBeRenamedWithoutAffectingOtherUsers() {
        runTest(fixture -> {
            UUID alice;
            UUID bob;
            try (Connection app = fixture.connectAsApplication()) {
                alice = insertUser(app, "Alice@Example.org");
                bob = insertUser(app, "bob@example.org");
            }
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "Initial name")),
                    get(dispatcher, alice));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "🌍 Alice")),
                    update(dispatcher, alice, "🌍 Alice"));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "🌍 Alice")),
                    get(dispatcher, alice));
            assertEquals(Result.ok(new AccountProfile(bob, "bob@example.org", "Initial name")),
                    get(dispatcher, bob));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "Initial name")),
                    update(dispatcher, alice, "Initial name"));
            assertEquals(Result.ok(new AccountProfile(alice, "Alice@Example.org", "Initial name")),
                    update(dispatcher, alice, "Initial name"));
            try (Connection app = fixture.connectAsApplication()) {
                assertEquals(1, countProfiles(app, alice));
                assertEquals(1, countProfiles(app, bob));
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
                assertEquals(1, countProfiles(app, alice));
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void simultaneousUpdatesPreserveOneProfileAndRollbackDoesNotPersist() {
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
                        assertTrue(probe.profiles().setDisplayNameIfActive(alice, DisplayName.from("Must roll back")).isPresent());
                        throw new IllegalStateException("induced rollback");
                    })));
            assertEquals(persisted, ((Result.Ok<AccountProfile, AccountProfileFailure>) get(dispatcher, alice))
                    .value().displayName());
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void publicLookupIncludesDisabledProfilesAndDistinguishesMissingIntegrityFailure() {
        runTest(fixture -> {
            UUID alice;
            UUID bob;
            try (Connection app = fixture.connectAsApplication()) {
                alice = insertUser(app, "Alice@example.org");
                bob = insertUser(app, "Bob@example.org");
                try (PreparedStatement disable = app.prepareStatement("update identity.users set disabled_at = now() where id = ?")) {
                    disable.setObject(1, bob);
                    disable.executeUpdate();
                }
            }
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            ProfileLookup command = ProfileLookup.tryFrom(java.util.List.of(bob.toString(), UUID.randomUUID().toString(), alice.toString(), bob.toString()))
                    .fold(value -> value, errors -> { throw new AssertionError(errors); });
            assertEquals(java.util.List.of(new PublicProfile(bob, "Initial name"), new PublicProfile(alice, "Initial name")),
                    dispatcher.dispatch(Probe.class, probe -> new ProfileLookupService(identity(alice), probe.transactions(), probe.profiles()).lookup(command)));
            try (Connection app = fixture.connectAsApplication(); PreparedStatement remove = app.prepareStatement("delete from identity.user_profiles where user_id = ?")) {
                remove.setObject(1, bob);
                remove.executeUpdate();
            }
            assertThrows(RuntimeException.class, () -> dispatcher.dispatch(Probe.class,
                    probe -> new ProfileLookupService(identity(alice), probe.transactions(), probe.profiles()).lookup(command)));
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    private static Result<AccountProfile, AccountProfileFailure> get(OperationDispatcher dispatcher, UUID userId) {
        return dispatcher.dispatch(Probe.class, probe ->
                service(userId, probe).get());
    }

    private static Result<AccountProfile, AccountProfileFailure> update(OperationDispatcher dispatcher, UUID userId,
            String displayName) {
        DisplayName command = ((Result.Ok<DisplayName, ?>) DisplayName.tryFrom(displayName))
                .value();
        return dispatcher.dispatch(Probe.class, probe -> service(userId, probe).setDisplayName(command));
    }

    private static AccountProfileService service(UUID userId, Probe probe) {
        return new AccountProfileService(identity(userId), probe.transactions(), probe.profiles());
    }

    private static IdentityContext identity(UUID userId) {
        return new IdentityContext() {
            @Override public Optional<UUID> userId() { return Optional.of(userId); }
            @Override public Optional<UUID> sessionId() { return Optional.of(userId); }
        };
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
                UUID id = rows.getObject(1, UUID.class);
                try (PreparedStatement profile = connection.prepareStatement(
                        "insert into identity.user_profiles(user_id, display_name) values (?, 'Initial name')")) {
                    profile.setObject(1, id);
                    assertEquals(1, profile.executeUpdate());
                }
                return id;
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
