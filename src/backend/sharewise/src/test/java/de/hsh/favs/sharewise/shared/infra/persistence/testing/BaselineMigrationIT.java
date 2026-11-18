package de.hsh.favs.sharewise.shared.infra.persistence.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** P1.2 baseline schema and role contract against real PostgreSQL (TC-3, TC-5, TC-10, TC-11, TC-12). */
class BaselineMigrationIT {

    private static final List<String> OWNED_SCHEMAS =
        List.of("identity", "groups", "finance", "files", "notifications", "flyway");

    private static PostgresTestDatabase database;

    @BeforeAll
    static void migrate() {
        database = PostgresTestDatabase.createMigrated();
    }

    @AfterAll
    static void drop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsOneSchemaPerContextOwnedByTheMigrationRole() throws SQLException {
        Map<String, String> owners = new HashMap<>();
        try (Connection admin = database.connectAsAdmin();
             Statement statement = admin.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT nspname, pg_get_userbyid(nspowner) FROM pg_namespace")) {
            while (rows.next()) {
                owners.put(rows.getString(1), rows.getString(2));
            }
        }
        for (String schema : OWNED_SCHEMAS) {
            assertEquals(PostgresTestDatabase.MIGRATOR_USER, owners.get(schema), "owner of schema " + schema);
        }
    }

    @Test
    void hasNoForeignKeysAcrossSchemas() throws SQLException {
        try (Connection admin = database.connectAsAdmin();
             Statement statement = admin.createStatement();
             ResultSet rows = statement.executeQuery("""
                 SELECT count(*)
                 FROM pg_constraint c
                 JOIN pg_class referencing ON referencing.oid = c.conrelid
                 JOIN pg_class referenced ON referenced.oid = c.confrelid
                 WHERE c.contype = 'f' AND referencing.relnamespace <> referenced.relnamespace
                 """)) {
            rows.next();
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void applicationRoleCannotChangeTheSchemaOrReadTheMigrationHistory() throws SQLException {
        try (Connection app = database.connectAsApplication()) {
            assertThrows(SQLException.class, () -> execute(app, "CREATE TABLE groups.it_ddl_probe (x int)"));
            assertThrows(SQLException.class, () -> execute(app, "CREATE SCHEMA it_ddl_probe"));
            assertThrows(SQLException.class, () -> execute(app, "CREATE TEMPORARY TABLE it_temp_probe (x int)"));
            assertThrows(SQLException.class, () -> execute(app, "SELECT * FROM flyway.flyway_schema_history"));
        }
    }

    @Test
    void applicationRoleUsesTablesCreatedByTheMigrationRoleWithGeneratedUuidV7Keys() throws SQLException {
        try (Connection migrator = database.connectAsMigrator()) {
            execute(migrator,
                "CREATE TABLE groups.it_privilege_probe (id uuid PRIMARY KEY DEFAULT uuidv7(), label text NOT NULL)");
        }
        try (Connection app = database.connectAsApplication();
             Statement statement = app.createStatement()) {
            statement.executeUpdate("INSERT INTO groups.it_privilege_probe (label) VALUES ('Mallorca 2027')");
            try (ResultSet rows = statement.executeQuery(
                "SELECT label, uuid_extract_version(id) FROM groups.it_privilege_probe")) {
                assertTrue(rows.next());
                assertEquals("Mallorca 2027", rows.getString(1));
                assertEquals(7, rows.getInt(2));
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
