package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import de.hsh.favs.sharewise.identity.internal.profile.DisplayName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** Real PostgreSQL 18 migrations, constraints, concurrent transactions and IAM jOOQ adapters. */
@Tag("e2e")
class IamPersistenceE2E extends E2ETestBase {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final SessionPolicy SESSIONS = SessionPolicy.initial();

    @Test
    void flywayCreatesTheExpectedTablesDefaultsAndApplicationRolePrivileges() {
        runTest(fixture -> {
            try (Connection connection = fixture.connectAsApplication();
                 Statement sql = connection.createStatement()) {
                for (String table : new String[]{"users", "sessions", "refresh_tokens"}) {
                    try (ResultSet rows = sql.executeQuery(
                            "select count(*) from identity." + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
                // Verify the *physical* domain (not merely an equivalent column CHECK).
                try (ResultSet columns = sql.executeQuery("""
                        select domain_schema, domain_name
                          from information_schema.columns
                         where table_schema = 'identity'
                           and table_name = 'users'
                           and column_name = 'failed_login_attempts'
                        """)) {
                    assertTrue(columns.next());
                    assertEquals("identity", columns.getString("domain_schema"));
                    assertEquals("natural0", columns.getString("domain_name"));
                    assertFalse(columns.next());
                }
                try (ResultSet grants = sql.executeQuery("""
                        select has_type_privilege(current_user, 'identity.natural0', 'USAGE')
                        """)) {
                    assertTrue(grants.next());
                    assertTrue(grants.getBoolean(1));
                }
                assertThrows(SQLException.class,
                        () -> sql.execute("alter table identity.users add column forbidden text"));
                assertThrows(SQLException.class, () -> sql.execute("select * from flyway.flyway_schema_history"));
            }

            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
                AccountSnapshot account = probe.accounts()
                        .insert(EmailAddress.parse("New@Example.com"), "encoded-password", DisplayName.from("Test user")).orElseThrow();
                LoginSession session = probe.sessions().insert(account.userId(), SESSIONS.startAt(NOW));
                UUID tokenId = probe.refresh().newTokenId();
                probe.refresh().insert(token(tokenId, session.id(), NOW));
                assertEquals(7, account.userId().version());
                assertEquals(7, session.id().version());
                assertEquals(7, tokenId.version());
                return TransactionOutcome.commit(null);
            }));
            try (Connection connection = fixture.connectAsApplication();
                 Statement sql = connection.createStatement();
                 ResultSet rows = sql.executeQuery("""
                         select u.email, u.normalized_email, u.failed_login_attempts,
                                s.user_id = u.id, r.session_id = s.id, octet_length(r.verifier_hash)
                           from identity.users u
                           join identity.sessions s on s.user_id = u.id
                           join identity.refresh_tokens r on r.session_id = s.id
                         """)) {
                assertTrue(rows.next());
                assertEquals("New@Example.com", rows.getString(1));
                assertEquals("new@example.com", rows.getString(2));
                assertEquals(0, rows.getInt(3));
                assertTrue(rows.getBoolean(4));
                assertTrue(rows.getBoolean(5));
                assertEquals(32, rows.getInt(6));
                assertFalse(rows.next());
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void databaseConstraintsRejectInvalidRowsAndKeepConsumedTokenHistory() {
        runTest(fixture -> {
            try (Connection app = fixture.connectAsApplication()) {
                UUID userId = insertUser(app, "a@example.com");
                assertThrows(SQLException.class, () -> execute(app, """
                        insert into identity.users(email, normalized_email, password_hash)
                        values ('ALIAS@example.com', 'a@example.com', 'encoded')
                        """));
                assertThrows(SQLException.class, () -> execute(app, """
                        insert into identity.users(email, normalized_email, password_hash)
                        values ('invalid@example.com', 'invalid@example.com', '')
                        """));
                assertThrows(SQLException.class, () -> execute(app,
                        "update identity.users set failed_login_attempts = -1 where id = '" + userId + "'"));
                assertThrows(SQLException.class, () -> execute(app, """
                        insert into identity.sessions(user_id, created_at, idle_expires_at, absolute_expires_at)
                        values (uuidv7(), now(), now() + interval '1 day', now() + interval '2 days')
                        """));
                assertThrows(SQLException.class, () -> execute(app,
                        "insert into identity.sessions(user_id, created_at, idle_expires_at, absolute_expires_at) "
                        + "values ('" + userId + "', now(), now() + interval '3 days', now() + interval '2 days')"));

                assertThrows(SQLException.class, () -> execute(app,
                        "insert into identity.sessions(user_id, created_at, idle_expires_at, absolute_expires_at) "
                        + "values ('" + userId + "', now(), now(), now() + interval '2 days')"));

                UUID sessionId = insertSession(app, userId);
                assertThrows(SQLException.class, () -> execute(app,
                        "insert into identity.refresh_tokens(session_id, verifier_hash) "
                        + "values ('" + sessionId + "', decode('deadbeef', 'hex'))"));
                assertThrows(SQLException.class, () -> execute(app, """
                        insert into identity.refresh_tokens(session_id, verifier_hash)
                        values (uuidv7(), decode(repeat('ab', 32), 'hex'))
                        """));
                insertToken(app, sessionId);
                assertThrows(SQLException.class, () -> insertToken(app, sessionId));
                execute(app, "update identity.refresh_tokens set consumed_at = issued_at "
                        + "where session_id = '" + sessionId + "'");
                insertToken(app, sessionId);
                try (Statement sql = app.createStatement();
                     ResultSet rows = sql.executeQuery(
                             "select count(*) from identity.refresh_tokens where session_id = '" + sessionId + "'")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
            }
        }, ProductionEntityScanConfiguration.class);
    }

    @Test
    void accountInsertIsConflictSafeAndFailureStateUpdatesAreConditional() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            UUID userId = dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
                AccountSnapshot account = probe.accounts().insert(
                        EmailAddress.parse("EXAMPLE@Example.org"), "hash", DisplayName.from("Test user")).orElseThrow();
                assertTrue(probe.accounts().insert(
                        EmailAddress.parse("example@example.org"), "hash2", DisplayName.from("Test user")).isEmpty());
                AccountSnapshot locked = probe.accounts().findByEmailForUpdate(
                        EmailAddress.parse("example@EXAMPLE.ORG")).orElseThrow();
                assertEquals(account.userId(), locked.userId());
                assertEquals("EXAMPLE@Example.org", locked.email().display());

                LoginLockoutPolicy.State initial = locked.loginFailures();
                LoginLockoutPolicy.State failed = new LoginLockoutPolicy.State(1, null);
                assertTrue(probe.accounts().compareAndSetLoginFailures(account.userId(), initial, failed));
                assertFalse(probe.accounts().compareAndSetLoginFailures(account.userId(), initial,
                        new LoginLockoutPolicy.State(2, null)));
                return TransactionOutcome.commit(account.userId());
            }));
            try (Connection app = fixture.connectAsApplication();
                 PreparedStatement query = app.prepareStatement(
                         "select failed_login_attempts from identity.users where id = ?")) {
                query.setObject(1, userId);
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void registrationTablesRollBackTogetherWhenASectionFails() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
                AccountSnapshot account = probe.accounts()
                        .insert(EmailAddress.parse("rollback@example.org"), "hash", DisplayName.from("Test user")).orElseThrow();
                LoginSession session = probe.sessions().insert(account.userId(), SESSIONS.startAt(NOW));
                probe.refresh().insert(token(probe.refresh().newTokenId(), session.id(), NOW));
                return TransactionOutcome.rollback(null);
            }));
            try (Connection app = fixture.connectAsApplication();
                 Statement sql = app.createStatement()) {
                for (String table : new String[]{"users", "sessions", "refresh_tokens"}) {
                    try (ResultSet rows = sql.executeQuery(
                            "select count(*) from identity." + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void failedThirdInsertRollsBackAccountAndSessionCreation() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            assertThrows(RuntimeException.class, () ->
                    dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
                        AccountSnapshot account = probe.accounts()
                                .insert(EmailAddress.parse("failed@example.org"), "hash", DisplayName.from("Test user")).orElseThrow();
                        LoginSession session = probe.sessions().insert(account.userId(), SESSIONS.startAt(NOW));
                        // A valid-length verifier cannot refer to a nonexistent token family.
                        probe.refresh().insert(token(probe.refresh().newTokenId(), UUID.randomUUID(), NOW));
                        return TransactionOutcome.commit(session.id());
                    })));
            try (Connection app = fixture.connectAsApplication();
                 Statement sql = app.createStatement()) {
                for (String table : new String[]{"users", "sessions", "refresh_tokens"}) {
                    try (ResultSet rows = sql.executeQuery("select count(*) from identity." + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void concurrentRegistrationsCannotCreateDuplicateCanonicalEmails() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Optional<AccountSnapshot>> first = register(dispatcher, start, "User@Example.org");
            FutureTask<Optional<AccountSnapshot>> second = register(dispatcher, start, "user@example.org");
            Thread.ofVirtual().start(first);
            Thread.ofVirtual().start(second);
            start.countDown();
            Optional<AccountSnapshot> a = first.get(10, TimeUnit.SECONDS);
            Optional<AccountSnapshot> b = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(a.isPresent(), b.isPresent());
            try (Connection app = fixture.connectAsApplication();
                 Statement sql = app.createStatement();
                 ResultSet rows = sql.executeQuery("select count(*) from identity.users")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    @Test
    void concurrentFailureUpdatesSerializeThroughAccountRowLock() {
        runTest(fixture -> {
            OperationDispatcher dispatcher = fixture.context().getBean(OperationDispatcher.class);
            dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
                probe.accounts().insert(EmailAddress.parse("login@example.org"), "hash", DisplayName.from("Test user"));
                return TransactionOutcome.commit(null);
            }));
            CountDownLatch firstLocked = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            FutureTask<Integer> first = new FutureTask<>(() -> increment(dispatcher, firstLocked, releaseFirst));
            FutureTask<Integer> second = new FutureTask<>(() -> {
                assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
                return increment(dispatcher, null, null);
            });
            Thread.ofVirtual().start(first);
            Thread.ofVirtual().start(second);
            try {
                assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            } finally {
                releaseFirst.countDown();
            }
            assertEquals(1, first.get(10, TimeUnit.SECONDS));
            assertEquals(2, second.get(10, TimeUnit.SECONDS));
        }, ProductionEntityScanConfiguration.class, Fixtures.class);
    }

    private static FutureTask<Optional<AccountSnapshot>> register(OperationDispatcher dispatcher,
            CountDownLatch start, String email) {
        return new FutureTask<>(() -> {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() ->
                    TransactionOutcome.commit(probe.accounts().insert(EmailAddress.parse(email), "hash", DisplayName.from("Test user")))));
        });
    }

    private static int increment(OperationDispatcher dispatcher, CountDownLatch locked,
            CountDownLatch release) {
        return dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
            AccountSnapshot account = probe.accounts()
                    .findByEmailForUpdate(EmailAddress.parse("login@example.org")).orElseThrow();
            if (locked != null) {
                locked.countDown();
            }
            if (release != null) {
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the login row lock", failure);
                }
            }
            LoginLockoutPolicy.State old = account.loginFailures();
            LoginLockoutPolicy.State next = new LoginLockoutPolicy.State(old.failedAttempts() + 1, null);
            assertTrue(probe.accounts().compareAndSetLoginFailures(account.userId(), old, next));
            return TransactionOutcome.commit(next.failedAttempts());
        }));
    }

    private static RefreshTokenRecord token(UUID tokenId, UUID sessionId, Instant when) {
        return new RefreshTokenRecord(tokenId, sessionId, new byte[32], when, null);
    }

    private static UUID insertUser(Connection connection, String email) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                insert into identity.users(email, normalized_email, password_hash)
                values (?, ?, 'hash') returning id
                """)) {
            query.setString(1, email);
            query.setString(2, email);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                UUID id = rows.getObject(1, UUID.class);
                assertNotNull(id);
                return id;
            }
        }
    }

    private static UUID insertSession(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                insert into identity.sessions(user_id, created_at, idle_expires_at, absolute_expires_at)
                values (?, now(), now() + interval '1 day', now() + interval '2 days') returning id
                """)) {
            query.setObject(1, userId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static void insertToken(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                insert into identity.refresh_tokens(session_id, verifier_hash)
                values (?, decode(repeat('ab', 32), 'hex'))
                """)) {
            query.setObject(1, sessionId);
            query.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    record Probe(Transactions transactions, AccountStore accounts, SessionStore sessions,
            RefreshTokenStore refresh) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Scope("operation")
        Probe probe(Transactions transactions, AccountStore accounts, SessionStore sessions,
                RefreshTokenStore refresh) {
            return new Probe(transactions, accounts, sessions, refresh);
        }
    }
}
