package de.hsh.favs.sharewise.tests.postgres;

import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresApplicationFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** PostgreSQL-backed Phase C acceptance for owned databases and uncached application contexts. */
@org.junit.jupiter.api.Tag("e2e")
class PostgresApplicationFixtureE2E {

    @Test
    void independentContextsUseMigratedDatabasesAndCommittedSeedData() throws SQLException {
        String firstName;
        String secondName;
        try (PostgresApplicationFixture first = newFixture("first");
             PostgresApplicationFixture second = newFixture("second")) {
            firstName = first.database().databaseName();
            secondName = second.database().databaseName();
            assertNotEquals(firstName, secondName);
            assertNotSame(first.context(), second.context());
            assertNotSame(first.dataSource(), second.dataSource());
            assertEquals("first", labels(first));
            assertEquals("second", labels(second));

            commitThroughProductionTransactions(first, "first-committed");
            assertEquals("first,first-committed", labels(first));
            assertEquals("second", labels(second));

            try (Connection app = first.dataSource().getConnection();
                 Statement statement = app.createStatement();
                 ResultSet user = statement.executeQuery("SELECT current_user")) {
                assertTrue(user.next());
                assertEquals(PostgresTestDatabase.APPLICATION_USER, user.getString(1));
            }
        }
        assertDatabaseAbsent(firstName);
        assertDatabaseAbsent(secondName);
    }

    @Test
    void parallelFixturesRemainIndependentAndReleaseBothDatabases() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        try (ExecutorService workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = workers.submit(() -> concurrentFixture("parallel-first", bothStarted));
            Future<String> second = workers.submit(() -> concurrentFixture("parallel-second", bothStarted));
            String firstName = first.get(90, TimeUnit.SECONDS);
            String secondName = second.get(90, TimeUnit.SECONDS);
            assertNotEquals(firstName, secondName);
            assertDatabaseAbsent(firstName);
            assertDatabaseAbsent(secondName);
        }
    }

    @Test
    void failedSeedTransactionDoesNotCommitAnyChanges() throws SQLException {
        try (PostgresApplicationFixture fixture = newFixture("original")) {
            assertThrows(IllegalArgumentException.class, () ->
                PostgresApplicationFixture.seedCommitted(fixture.database(), app -> {
                    try (Statement statement = app.createStatement()) {
                        statement.executeUpdate(
                            "INSERT INTO groups.it_fixture_probe (label) VALUES ('not-committed')");
                    }
                    throw new IllegalArgumentException("abort seed");
                }));
            assertEquals("original", labels(fixture));
        }
    }

    @Test
    void closingTheFixtureInvalidatesItsTestFacingAccess() throws SQLException {
        PostgresApplicationFixture fixture = newFixture("closed");
        String name = fixture.database().databaseName();
        fixture.close();
        assertThrows(IllegalStateException.class, fixture::context);
        assertThrows(IllegalStateException.class, fixture::connectAsApplication);
        fixture.close();
        assertDatabaseAbsent(name);
    }

    @Test
    void failedPreparationDropsOnlyItsOwnedDatabase() throws SQLException {
        AtomicReference<String> name = new AtomicReference<>();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> PostgresApplicationFixture.start(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> {
                    name.set(database.databaseName());
                    throw new IllegalArgumentException("intentional seed failure");
                }, ShareWiseApplication.class, ProductionEntityScanConfiguration.class));
        assertEquals("intentional seed failure", failure.getMessage());
        assertDatabaseAbsent(name.get());
    }

    @Test
    void failedSpringStartupAlsoDropsTheProvisionedDatabase() throws SQLException {
        AtomicReference<String> name = new AtomicReference<>();
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> PostgresApplicationFixture.start(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database ->
                    name.set(database.databaseName()),
                ShareWiseApplication.class, ProductionEntityScanConfiguration.class, BrokenConfiguration.class));
        assertTrue(hasMessage(failure, "intentional Spring startup failure"));
        assertDatabaseAbsent(name.get());
    }

    private static PostgresApplicationFixture newFixture(String seed) {
        return PostgresApplicationFixture.start(
            PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> {
                try (Connection migrator = database.connectAsMigrator();
                     Statement statement = migrator.createStatement()) {
                    statement.execute("CREATE TABLE groups.it_fixture_probe (label text PRIMARY KEY)");
                }
                PostgresApplicationFixture.seedCommitted(database, app -> {
                    try (PreparedStatement statement = app.prepareStatement(
                        "INSERT INTO groups.it_fixture_probe (label) VALUES (?)")) {
                        statement.setString(1, seed);
                        statement.executeUpdate();
                    }
                });
            }, ShareWiseApplication.class, ProductionEntityScanConfiguration.class);
    }

    private static String concurrentFixture(String value, CountDownLatch bothStarted) throws Exception {
        try (PostgresApplicationFixture fixture = newFixture(value)) {
            String name = fixture.database().databaseName();
            bothStarted.countDown();
            assertTrue(bothStarted.await(40, TimeUnit.SECONDS), "parallel fixture initialization timed out");
            commitThroughProductionTransactions(fixture, value + "-committed");
            assertEquals(value + "," + value + "-committed", labels(fixture));
            return name;
        }
    }

    private static void commitThroughProductionTransactions(PostgresApplicationFixture fixture, String value) {
        ServiceScopeFactory scopes = fixture.context().getBean(ServiceScopeFactory.class);
        new OperationTestHarness(scopes).inOperationScope(scope -> {
            Transactions transactions = scope.resolve(Transactions.class);
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            transactions.execute(() -> {
                jooq.dsl().execute("INSERT INTO groups.it_fixture_probe (label) VALUES (?)", value);
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    private static String labels(PostgresApplicationFixture fixture) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT label FROM groups.it_fixture_probe ORDER BY label")) {
            java.util.List<String> values = new java.util.ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return String.join(",", values);
        }
    }

    private static void assertDatabaseAbsent(String name) throws SQLException {
        assertFalse(name == null || name.isBlank(), "no fixture identifier was recorded");
        PostgresTestDatabase.ConnectionSettings settings = PostgresTestDatabase.ConnectionSettings.fromEnvironment();
        try (Connection admin = java.sql.DriverManager.getConnection(
                 settings.adminUrl(), settings.adminUser(), settings.adminPassword());
             PreparedStatement statement = admin.prepareStatement(
                 "SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                assertFalse(result.next(), "fixture database was not dropped: " + name);
            }
        }
    }

    private static boolean hasMessage(Throwable failure, String text) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenConfiguration {
        @Bean
        Object failingFixtureBean() {
            throw new IllegalStateException("intentional Spring startup failure");
        }
    }
}
