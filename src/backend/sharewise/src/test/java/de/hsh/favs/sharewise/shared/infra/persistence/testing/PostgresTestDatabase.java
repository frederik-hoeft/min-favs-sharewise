package de.hsh.favs.sharewise.shared.infra.persistence.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * A disposable PostgreSQL database for integration tests.
 *
 * <p>Connects to an external PostgreSQL server with administrative credentials: the dev-container
 * {@code postgres} service by default, or the server given by {@code SHAREWISE_TEST_DB_URL},
 * {@code SHAREWISE_TEST_DB_ADMIN_USER} and {@code SHAREWISE_TEST_DB_ADMIN_PASSWORD} (for example the
 * CI service). Each instance creates its own database, runs the shared role bootstrap scripts and
 * drops the database again on {@link #close()}. If no server is reachable, creation fails with a
 * descriptive {@link IllegalStateException}; tests never fall back to H2 or mocks.
 */
public final class PostgresTestDatabase implements AutoCloseable {

    public static final String APPLICATION_USER = "sharewise_app";
    public static final String APPLICATION_PASSWORD = "sharewise_app_dev";
    public static final String MIGRATOR_USER = "sharewise_migrator";
    public static final String MIGRATOR_PASSWORD = "sharewise_migrator_dev";

    private static final String ADMIN_URL =
        setting("SHAREWISE_TEST_DB_URL", "jdbc:postgresql://postgres:5432/postgres");
    private static final String ADMIN_USER = setting("SHAREWISE_TEST_DB_ADMIN_USER", "postgres");
    private static final String ADMIN_PASSWORD = setting("SHAREWISE_TEST_DB_ADMIN_PASSWORD", "postgres_dev");

    private final String name;
    private final String jdbcUrl;

    private PostgresTestDatabase(String name, String jdbcUrl) {
        this.name = name;
        this.jdbcUrl = jdbcUrl;
    }

    /** Creates a new database with the ShareWise roles and database grants, without migrations. */
    public static PostgresTestDatabase createBootstrapped() {
        String name = "sharewise_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute(bootstrapScript("01-create-roles.sql"));
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL for integration tests is not reachable at " + ADMIN_URL
                + " (user " + ADMIN_USER + "). Start the dev-container database or set SHAREWISE_TEST_DB_URL, "
                + "SHAREWISE_TEST_DB_ADMIN_USER and SHAREWISE_TEST_DB_ADMIN_PASSWORD.", e);
        }
        PostgresTestDatabase database = new PostgresTestDatabase(name, withDatabaseName(ADMIN_URL, name));
        try (Connection admin = database.connectAsAdmin(); Statement statement = admin.createStatement()) {
            statement.execute(bootstrapScript("02-grant-database-access.sql"));
        } catch (SQLException e) {
            database.close();
            throw new IllegalStateException("Could not grant database access on " + name, e);
        }
        return database;
    }

    /** Creates a new bootstrapped database and applies all Flyway migrations as the migration role. */
    public static PostgresTestDatabase createMigrated() {
        PostgresTestDatabase database = createBootstrapped();
        try {
            Flyway.configure()
                .dataSource(database.jdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .load()
                .migrate();
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
        return database;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, APPLICATION_USER, APPLICATION_PASSWORD);
    }

    public Connection connectAsMigrator() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD);
    }

    public Connection connectAsAdmin() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, ADMIN_USER, ADMIN_PASSWORD);
    }

    @Override
    public void close() {
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not drop test database " + name, e);
        }
    }

    private static String withDatabaseName(String url, String databaseName) {
        int queryStart = url.indexOf('?');
        String base = queryStart < 0 ? url : url.substring(0, queryStart);
        String query = queryStart < 0 ? "" : url.substring(queryStart);
        return base.substring(0, base.lastIndexOf('/') + 1) + databaseName + query;
    }

    private static String bootstrapScript(String fileName) {
        String resource = "db/bootstrap/" + fileName;
        try (InputStream in = PostgresTestDatabase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bootstrap script on the test classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
