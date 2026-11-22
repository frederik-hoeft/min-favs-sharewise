package de.hsh.favs.sharewise.tests.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.testsupport.postgres.IsolatedPostgresPool;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Database-free checks of the strict fixture configuration and missing-service behavior. */
class PostgresFixtureConfigurationTests {

    @Test
    void isolatedFixturesDoNotPreallocateTenConnectionsPerContext() {
        Map<String, Object> properties = new HashMap<>();
        IsolatedPostgresPool.configure(properties);
        assertEquals(3, properties.get("spring.datasource.hikari.maximum-pool-size"));
        assertEquals(0, properties.get("spring.datasource.hikari.minimum-idle"));
    }

    @Test
    void springTestContextFixturesUseTheSamePoolBudget() {
        Map<String, Supplier<Object>> properties = new HashMap<>();
        DynamicPropertyRegistry registry = properties::put;
        IsolatedPostgresPool.register(registry);
        assertEquals(3, properties.get("spring.datasource.hikari.maximum-pool-size").get());
        assertEquals(0, properties.get("spring.datasource.hikari.minimum-idle").get());
    }

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
            () -> IntegrationTestContext.start(settings, database -> { }));
    }

    @Test
    void unavailableServerFailsClosedWithoutAnEmbeddedDatabaseFallback() {
        PostgresTestDatabase.ConnectionSettings settings = new PostgresTestDatabase.ConnectionSettings(
            "jdbc:postgresql://127.0.0.1:1/postgres?connectTimeout=1", "postgres", "admin", "migrator", "app");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> IntegrationTestContext.start(settings, database -> { }, ShareWiseApplication.class));
        assertTrue(failure.getMessage().contains("not reachable"), failure.getMessage());
    }
}
