package de.hsh.favs.sharewise.testsupport.postgres;

import de.hsh.favs.sharewise.testsupport.database.TestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Production Flyway scripts for tests that execute a data migration outside Flyway, for example
 * to prove that re-execution changes nothing or to apply it to state seeded after the fixture
 * migrated. Scripts run as the migrator, the role Flyway uses.
 */
public final class MigrationScripts {

    private MigrationScripts() {
    }

    /** Reads a script by classpath location, such as {@code db/migration/platform/V…sql}. */
    public static String read(String location) {
        Objects.requireNonNull(location, "location");
        try (InputStream input = MigrationScripts.class.getClassLoader().getResourceAsStream(location)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing classpath resource " + location);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read " + location, failure);
        }
    }

    /** Executes the whole script as the migrator in one implicit transaction. */
    public static void executeAsMigrator(TestDatabase database, String location) throws SQLException {
        Objects.requireNonNull(database, "database");
        String script = read(location);
        try (Connection migrator = database.connectAsMigrator(); Statement statement = migrator.createStatement()) {
            statement.execute(script);
        }
    }
}
