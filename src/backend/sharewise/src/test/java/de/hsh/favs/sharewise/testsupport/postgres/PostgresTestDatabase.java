package de.hsh.favs.sharewise.testsupport.postgres;

import de.hsh.favs.sharewise.testsupport.database.TestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
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
public final class PostgresTestDatabase implements TestDatabase {

    public static final String APPLICATION_USER = "sharewise_app";
    public static final String APPLICATION_PASSWORD = "sharewise_app_dev";
    public static final String MIGRATOR_USER = "sharewise_migrator";
    public static final String MIGRATOR_PASSWORD = "sharewise_migrator_dev";

    /**
     * Connection credentials for a PostgreSQL server and its ShareWise roles. Role names are
     * fixed by P1.2's bootstrap SQL. Password overrides require matching pre-provisioned roles;
     * the bootstrap SQL itself installs the default development passwords.
     */
    public record ConnectionSettings(
        String adminUrl,
        String adminUser,
        String adminPassword,
        String migratorPassword,
        String applicationPassword
    ) {
        public ConnectionSettings {
            Objects.requireNonNull(adminUrl, "adminUrl");
            Objects.requireNonNull(adminUser, "adminUser");
            Objects.requireNonNull(adminPassword, "adminPassword");
            Objects.requireNonNull(migratorPassword, "migratorPassword");
            Objects.requireNonNull(applicationPassword, "applicationPassword");
            String prefix = "jdbc:postgresql://";
            String endpoint = adminUrl.split("\\?", 2)[0];
            int databaseSeparator = endpoint.lastIndexOf('/');
            if (!endpoint.startsWith(prefix) || databaseSeparator <= prefix.length()
                    || databaseSeparator == endpoint.length() - 1) {
                throw new IllegalArgumentException("Expected a PostgreSQL JDBC URL with a database name");
            }
        }

        public static ConnectionSettings fromEnvironment() {
            return new ConnectionSettings(
                setting("SHAREWISE_TEST_DB_URL", "jdbc:postgresql://postgres:5432/postgres"),
                setting("SHAREWISE_TEST_DB_ADMIN_USER", "postgres"),
                setting("SHAREWISE_TEST_DB_ADMIN_PASSWORD", "postgres_dev"),
                setting("SHAREWISE_TEST_DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD),
                setting("SHAREWISE_TEST_DB_APPLICATION_PASSWORD", APPLICATION_PASSWORD));
        }
    }

    private final String name;
    private final String jdbcUrl;
    private final ConnectionSettings connections;

    private PostgresTestDatabase(String name, ConnectionSettings connections) {
        this.name = name;
        this.connections = connections;
        this.jdbcUrl = withDatabaseName(connections.adminUrl(), name);
    }

    /** Creates a new database with the ShareWise roles and database grants, without migrations. */
    public static PostgresTestDatabase createBootstrapped() {
        return createBootstrapped(ConnectionSettings.fromEnvironment());
    }

    /** Uses explicit connection settings for a database owned exclusively by this fixture. */
    public static PostgresTestDatabase createBootstrapped(ConnectionSettings connections) {
        Objects.requireNonNull(connections, "connections");
        String name = "sharewise_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                 connections.adminUrl(), connections.adminUser(), connections.adminPassword());
             Statement statement = admin.createStatement()) {
            // Serialize cluster-wide role bootstrap across parallel fixtures. Closing the
            // administrative session releases the advisory lock, including on failure.
            statement.execute("SELECT pg_advisory_lock(147818, 1303)");
            statement.execute(bootstrapScript("01-create-roles.sql"));
            // PostgreSQL CREATE DATABASE cannot execute inside a transaction block.
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            if (isConnectionFailure(e)) {
                throw new IllegalStateException("PostgreSQL for integration tests is not reachable at "
                    + connections.adminUrl() + " (user " + connections.adminUser()
                    + "). Start the dev-container database or set SHAREWISE_TEST_DB_URL, "
                    + "SHAREWISE_TEST_DB_ADMIN_USER and SHAREWISE_TEST_DB_ADMIN_PASSWORD.", e);
            }
            throw new IllegalStateException("Could not bootstrap roles or create test database " + name
                + " on " + connections.adminUrl(), e);
        }
        PostgresTestDatabase database = new PostgresTestDatabase(name, connections);
        try (Connection admin = database.connectAsAdmin(); Statement statement = admin.createStatement()) {
            statement.execute(bootstrapScript("02-grant-database-access.sql"));
        } catch (SQLException e) {
            IllegalStateException failure = new IllegalStateException("Could not grant database access on " + name, e);
            closeSuppressing(database, failure);
            throw failure;
        }
        return database;
    }

    /** Creates a new bootstrapped database and applies all Flyway migrations as the migration role. */
    public static PostgresTestDatabase createMigrated() {
        return createMigrated(ConnectionSettings.fromEnvironment());
    }

    /** Reuses P1.2's migration path with explicit connection settings. */
    public static PostgresTestDatabase createMigrated(ConnectionSettings connections) {
        PostgresTestDatabase database = createBootstrapped(connections);
        try {
            Flyway.configure()
                .dataSource(database.jdbcUrl, MIGRATOR_USER, connections.migratorPassword())
                .locations("classpath:db/migration")
                .defaultSchema("flyway")
                .schemas("flyway")
                .load()
                .migrate();
        } catch (RuntimeException e) {
            closeSuppressing(database, e);
            throw e;
        }
        return database;
    }

    /** The generated identifier of the database created and owned by this fixture. */
    public String databaseName() {
        return name;
    }

    public ConnectionSettings connectionSettings() {
        return connections;
    }

    /** Administrative server connection, including after the fixture database has been dropped. */
    public Connection connectToServerAsAdmin() throws SQLException {
        return DriverManager.getConnection(
            connections.adminUrl(), connections.adminUser(), connections.adminPassword());
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, APPLICATION_USER, connections.applicationPassword());
    }

    public Connection connectAsMigrator() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, MIGRATOR_USER, connections.migratorPassword());
    }

    public Connection connectAsAdmin() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, connections.adminUser(), connections.adminPassword());
    }

    @Override
    public void close() {
        try (Connection admin = connectToServerAsAdmin();
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not drop test database " + name, e);
        }
    }

    private static void closeSuppressing(PostgresTestDatabase database, Throwable failure) {
        try {
            database.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** SQLSTATE class 08 ("connection exception") or no SQLSTATE at all (socket-level failure). */
    private static boolean isConnectionFailure(SQLException e) {
        String state = e.getSQLState();
        return state == null || state.startsWith("08");
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
