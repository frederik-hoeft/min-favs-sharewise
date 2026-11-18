package de.hsh.favs.sharewise.tests.postgres;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresApplicationFixture;
import org.junit.jupiter.api.Test;

/** Database-free checks of the strict fixture configuration and missing-service behavior. */
class PostgresFixtureConfigurationTests {

    @Test
    void rejectsIncompletePostgresEndpoint() {
        assertThrows(IllegalArgumentException.class,
            () -> new PostgresTestDatabase.ConnectionSettings(
                "jdbc:postgresql://localhost:5432", "postgres", "admin", "migrator", "app"));
    }

    @Test
    void refusesToCreateAContextWithoutAnApplicationSource() {
        PostgresTestDatabase.ConnectionSettings settings = new PostgresTestDatabase.ConnectionSettings(
            "jdbc:postgresql://localhost:5432/postgres", "postgres", "admin", "migrator", "app");
        assertThrows(IllegalArgumentException.class,
            () -> PostgresApplicationFixture.start(settings, database -> { }));
    }

    @Test
    void unavailableServerFailsClosedWithoutAnEmbeddedDatabaseFallback() {
        PostgresTestDatabase.ConnectionSettings settings = new PostgresTestDatabase.ConnectionSettings(
            "jdbc:postgresql://127.0.0.1:1/postgres?connectTimeout=1", "postgres", "admin", "migrator", "app");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> PostgresApplicationFixture.start(settings, database -> { }, ShareWiseApplication.class));
        assertTrue(failure.getMessage().contains("not reachable"), failure.getMessage());
    }
}
