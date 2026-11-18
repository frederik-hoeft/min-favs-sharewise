package de.hsh.favs.sharewise.testsupport.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Owns a fresh, migrated PostgreSQL database and an uncached production Spring context for one
 * real-commit test scenario. Closing the context releases its connection pool before database drop.
 * No Docker API or Spring TestContext caching participates in fixture creation.
 */
public final class PostgresWorkflowFixture implements AutoCloseable {

    @FunctionalInterface
    public interface DatabasePreparation {
        /** Runs after Flyway migration but before the application context starts. */
        void prepare(PostgresTestDatabase database) throws Exception;
    }

    @FunctionalInterface
    public interface DatabaseSeed {
        void seed(Connection connection) throws SQLException;
    }

    /** Seeds as the application role in a physically committed, independently owned transaction. */
    public static void seedCommitted(PostgresTestDatabase database, DatabaseSeed seeder) throws SQLException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(seeder, "seeder");
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            try {
                seeder.seed(connection);
                connection.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private final PostgresTestDatabase database;
    private final ConfigurableApplicationContext context;
    private boolean closed;

    private PostgresWorkflowFixture(PostgresTestDatabase database, ConfigurableApplicationContext context) {
        this.database = database;
        this.context = context;
    }

    /** Starts an independent context with the supplied production application and test configurations. */
    public static PostgresWorkflowFixture start(Class<?>... applicationSources) {
        return start(PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> { },
            applicationSources);
    }

    /** Uses explicit PostgreSQL server/role credentials and an optional committed-data initializer. */
    public static PostgresWorkflowFixture start(
        PostgresTestDatabase.ConnectionSettings connections,
        DatabasePreparation preparation,
        Class<?>... applicationSources
    ) {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(applicationSources, "applicationSources");
        if (applicationSources.length == 0) {
            throw new IllegalArgumentException("At least one application source is required");
        }

        PostgresTestDatabase database = PostgresTestDatabase.createMigrated(connections);
        try {
            preparation.prepare(database);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("spring.datasource.url", database.jdbcUrl());
            properties.put("spring.datasource.username", PostgresTestDatabase.APPLICATION_USER);
            properties.put("spring.datasource.password", connections.applicationPassword());
            // Flyway has already migrated this fixture with the migrator, before context startup.
            properties.put("spring.flyway.enabled", "false");
            properties.put("spring.jpa.hibernate.ddl-auto", "validate");
            properties.put("spring.jpa.open-in-view", "false");

            ConfigurableApplicationContext context = new SpringApplicationBuilder(applicationSources)
                .web(WebApplicationType.NONE)
                .initializers(application -> application.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("isolated-postgres-fixture", properties)))
                .run();
            return new PostgresWorkflowFixture(database, context);
        } catch (Throwable failure) {
            try {
                database.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to prepare PostgreSQL workflow fixture", failure);
        }
    }

    public PostgresTestDatabase database() {
        requireOpen();
        return database;
    }

    public ConfigurableApplicationContext context() {
        requireOpen();
        return context;
    }

    /** Opens an independent, application-role connection for committed-state assertions. */
    public Connection connectAsApplication() throws SQLException {
        requireOpen();
        return database.connectAsApplication();
    }

    public DataSource dataSource() {
        return context().getBean(DataSource.class);
    }

    private void requireOpen() {
        if (closed || !context.isActive()) {
            throw new IllegalStateException("PostgreSQL workflow fixture is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        try {
            context.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        }
        try {
            database.close();
        } catch (RuntimeException | Error dropFailure) {
            if (failure == null) {
                failure = dropFailure;
            } else {
                failure.addSuppressed(dropFailure);
            }
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
    }
}
