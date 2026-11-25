package de.hsh.favs.sharewise.groups.internal.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Physical invitation schema contract, independent of generated jOOQ mappings. */
@Tag("e2e")
class GroupInvitationSchemaE2E {
    // Test-owned literals only; no external input enters these SQL statements.
    private static final String GROUP = "'10000000-0000-0000-0000-000000000001'";
    private static final String USER = "'20000000-0000-0000-0000-000000000001'";
    private static final String MISSING_GROUP = "'30000000-0000-0000-0000-000000000001'";
    private static final String HASH = "decode(repeat('ab', 32), 'hex')";
    private static final String INSERT = "INSERT INTO groups.invitations (group_id, verifier_hash) VALUES (";
    private static final String BOUNDED_INSERT = "INSERT INTO groups.invitations "
            + "(group_id, verifier_hash, created_at, expires_at, max_uses, use_count) VALUES (";
    private static final String CREATED_AT = "'2026-11-25T12:00:00Z'";
    private static final String EXPIRES_AT = "'2026-11-26T12:00:00Z'";

    @Test
    void migratedSchemaHasLocalDomainsKeysIndexAndNarrowRuntimePrivileges() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection app = database.connectAsApplication();
             Connection migrator = database.connectAsMigrator()) {
            assertEquals(List.of(
                "id:pg_catalog.uuid:NO:true", "group_id:pg_catalog.uuid:NO:false",
                "verifier_hash:pg_catalog.bytea:NO:false", "created_at:pg_catalog.timestamptz:NO:true",
                "expires_at:pg_catalog.timestamptz:YES:false", "max_uses:groups.natural1:YES:false",
                "use_count:groups.natural0:NO:true"
            ), strings(app, """
                SELECT column_name || ':' || coalesce(domain_schema, udt_schema) || '.'
                    || coalesce(domain_name, udt_name) || ':' || is_nullable
                    || ':' || (column_default IS NOT NULL)::text
                FROM information_schema.columns
                WHERE table_schema = 'groups' AND table_name = 'invitations'
                ORDER BY ordinal_position
                """));
            assertEquals(List.of("natural0:integer", "natural1:integer"), strings(migrator, """
                SELECT typname || ':' || format_type(typbasetype, NULL)
                FROM pg_type WHERE typnamespace = 'groups'::regnamespace AND typtype = 'd'
                    AND typname IN ('natural0', 'natural1') ORDER BY typname
                """));
            assertEquals(List.of("groups.groups:a:a:group_id:id"), strings(migrator, """
                SELECT target_ns.nspname || '.' || target.relname || ':' || c.confupdtype::text
                    || ':' || c.confdeltype::text || ':' || source_column.attname || ':' || target_column.attname
                FROM pg_constraint c
                JOIN pg_class target ON target.oid = c.confrelid
                JOIN pg_namespace target_ns ON target_ns.oid = target.relnamespace
                JOIN pg_attribute source_column ON source_column.attrelid = c.conrelid
                    AND source_column.attnum = ANY(c.conkey)
                JOIN pg_attribute target_column ON target_column.attrelid = c.confrelid
                    AND target_column.attnum = ANY(c.confkey)
                WHERE c.conrelid = 'groups.invitations'::regclass AND c.contype = 'f'
                """));
            assertEquals(List.of("id"), strings(migrator, """
                SELECT a.attname FROM pg_constraint c JOIN pg_attribute a
                    ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                WHERE c.conrelid = 'groups.invitations'::regclass AND c.contype = 'p'
                """));
            assertEquals("true", scalar(migrator, """
                SELECT EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute a
                    ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                    WHERE i.indrelid = 'groups.invitations'::regclass AND a.attname = 'group_id'
                        AND i.indisvalid AND i.indpred IS NULL)::text
                """));
            assertEquals("sharewise_migrator", scalar(migrator, """
                SELECT tableowner FROM pg_tables WHERE schemaname = 'groups' AND tablename = 'invitations'
                """));
            for (String privilege : List.of("SELECT", "INSERT", "DELETE")) {
                assertEquals("true", scalar(app,
                    "SELECT has_table_privilege('groups.invitations', '" + privilege + "')::text"));
            }
            for (String privilege : List.of("UPDATE", "TRUNCATE", "TRIGGER", "REFERENCES")) {
                assertEquals("false", scalar(app,
                    "SELECT has_table_privilege('groups.invitations', '" + privilege + "')::text"));
            }
            assertEquals(List.of("use_count"), strings(app, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'groups' AND table_name = 'invitations'
                    AND has_column_privilege('groups.invitations', column_name::text, 'UPDATE')
                """));
            for (String type : List.of("natural0", "natural1")) {
                assertEquals("true", scalar(app, "SELECT has_type_privilege('groups." + type + "', 'USAGE')::text"));
            }
            assertEquals("0", scalar(migrator, """
                SELECT count(*) FROM pg_type t
                CROSS JOIN LATERAL aclexplode(coalesce(t.typacl, acldefault('T', t.typowner))) a
                WHERE t.typnamespace = 'groups'::regnamespace AND t.typname IN ('natural0', 'natural1')
                    AND a.grantee = 0 AND a.privilege_type = 'USAGE'
                """));
            assertSqlState(app, "42501", "ALTER TABLE groups.invitations ADD COLUMN forbidden text");
            assertSqlState(app, "42501", "ALTER DOMAIN groups.natural0 DROP CONSTRAINT groups_natural0_nonnegative");
        }
    }

    @Test
    void validUnlimitedAndBoundedLinksEnforceIdentityAndGroupOwnership() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection app = database.connectAsApplication()) {
            createGroup(app);
            assertEquals("true", scalar(app, INSERT + GROUP + ", " + HASH + ") RETURNING "
                + "(uuid_extract_version(id) = 7 AND created_at = now() AND expires_at IS NULL "
                + "AND max_uses IS NULL AND use_count = 0 AND verifier_hash = " + HASH + ")::text"));
            String id = scalar(app, boundedInsert(EXPIRES_AT, "1", "0") + " RETURNING id::text");
            String where = " WHERE id = '" + id + "'";
            assertEquals("1", scalar(app, "UPDATE groups.invitations SET use_count = use_count + 1" + where
                + " RETURNING use_count::text"));
            assertSqlState(app, "23514", "UPDATE groups.invitations SET use_count = use_count + 1" + where);
            assertSqlState(app, "23514", "UPDATE groups.invitations SET use_count = -1" + where);
            assertSqlState(app, "23502", "UPDATE groups.invitations SET use_count = NULL" + where);
            for (String assignment : List.of("id = id", "group_id = group_id", "verifier_hash = verifier_hash",
                    "created_at = created_at", "expires_at = expires_at", "max_uses = max_uses")) {
                assertSqlState(app, "42501", "UPDATE groups.invitations SET " + assignment + where);
            }
            assertSqlState(app, "23505", "INSERT INTO groups.invitations (id, group_id, verifier_hash) VALUES ('"
                + id + "', " + GROUP + ", " + HASH + ")");
            assertSqlState(app, "23503", INSERT + MISSING_GROUP + ", " + HASH + ")");
            assertSqlState(app, "23503", "DELETE FROM groups.groups WHERE id = " + GROUP);
            assertEquals("2", scalar(app, "SELECT count(*) FROM groups.invitations WHERE group_id = " + GROUP));
            execute(app, "DELETE FROM groups.invitations WHERE group_id = " + GROUP);
            execute(app, "DELETE FROM groups.groups WHERE id = " + GROUP);
            assertEquals("0", scalar(app, "SELECT count(*) FROM groups.invitations"));
        }
    }

    @Test
    void rejectsInvalidVerifierExpirationAndUsageBoundsWithoutPartialWrites() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection app = database.connectAsApplication()) {
            createGroup(app);
            assertSqlState(app, "23502", INSERT + "NULL, " + HASH + ")");
            assertSqlState(app, "23502", INSERT + GROUP + ", NULL)");
            assertSqlState(app, "23502", "INSERT INTO groups.invitations (id, group_id, verifier_hash) VALUES (NULL, "
                + GROUP + ", " + HASH + ")");
            assertSqlState(app, "23502", "INSERT INTO groups.invitations (group_id, verifier_hash, created_at) VALUES ("
                + GROUP + ", " + HASH + ", NULL)");
            for (int size : List.of(0, 31, 33)) {
                assertSqlState(app, "23514", INSERT + GROUP + ", decode(repeat('ab', " + size + "), 'hex'))");
            }
            assertSqlState(app, "23514", boundedInsert(CREATED_AT, "1", "0"));
            assertSqlState(app, "23514", boundedInsert("'2026-11-24T12:00:00Z'", "1", "0"));
            assertSqlState(app, "23514", boundedInsert(EXPIRES_AT, "0", "0"));
            assertSqlState(app, "23514", boundedInsert(EXPIRES_AT, "-1", "0"));
            assertSqlState(app, "23514", boundedInsert(EXPIRES_AT, "1", "-1"));
            assertSqlState(app, "23514", boundedInsert(EXPIRES_AT, "1", "2"));
            assertSqlState(app, "23502", boundedInsert(EXPIRES_AT, "1", "NULL"));
            assertEquals("0", scalar(app, "SELECT count(*) FROM groups.invitations"));
            // Unlimited links still count new members; the integer must never wrap.
            String id = scalar(app, boundedInsert("NULL", "NULL", "2147483647") + " RETURNING id::text");
            String where = " WHERE id = '" + id + "'";
            assertSqlState(app, "22003", "UPDATE groups.invitations SET use_count = use_count + 1" + where);
            assertEquals("2147483647", scalar(app, "SELECT use_count::text FROM groups.invitations" + where));
        }
    }

    @Test
    void migrationUpgradesExistingGroupsWithoutChangingMembershipOrMetadata() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            FluentConfiguration configuration = Flyway.configure()
                .dataSource(database.jdbcUrl(), PostgresTestDatabase.MIGRATOR_USER,
                    database.connectionSettings().migratorPassword())
                .locations("classpath:db/migration").defaultSchema("flyway").schemas("flyway")
                .outOfOrder(false);
            // Historical P2.2a schema, before invitations. Finance's 202611241838 precedes Group creation.
            Flyway baseline = configuration.target("202611251200").load();
            baseline.migrate();
            assertEquals(MigrationVersion.fromVersion("202611251200"), baseline.info().current().getVersion());
            try (Connection app = database.connectAsApplication()) {
                assertEquals("true", scalar(app, "SELECT (to_regclass('groups.groups') IS NOT NULL)::text"),
                    "The historical baseline must contain the Group root table");
                assertEquals("true", scalar(app, "SELECT (to_regclass('groups.invitations') IS NULL)::text"),
                    "The historical baseline must precede the invitation migration");
                createGroup(app);
                execute(app, "UPDATE groups.groups SET version = 4 WHERE id = " + GROUP);
                execute(app, "INSERT INTO groups.users (id) VALUES (" + USER + ")");
                String member = scalar(app, "INSERT INTO groups.group_members (group_id, user_id, role) VALUES ("
                    + GROUP + ", " + USER + ", 'admin') RETURNING id::text");
                Flyway upgraded = configuration.target(MigrationVersion.LATEST).load();
                upgraded.migrate();
                upgraded.validate();
                assertEquals("4", scalar(app, "SELECT version::text FROM groups.groups WHERE id = " + GROUP));
                assertEquals("Existing", scalar(app, "SELECT name FROM groups.groups WHERE id = " + GROUP));
                assertEquals("admin", scalar(app, "SELECT role::text FROM groups.group_members WHERE id = '" + member + "'"));
                assertEquals("0", scalar(app, "SELECT count(*) FROM groups.invitations"));
                execute(app, INSERT + GROUP + ", " + HASH + ")");
            }
        }
    }

    private static void createGroup(Connection app) throws SQLException {
        execute(app, "INSERT INTO groups.groups (id, name) VALUES (" + GROUP + ", 'Existing')");
    }

    private static String boundedInsert(String expires, String max, String count) {
        return BOUNDED_INSERT + GROUP + ", " + HASH + ", " + CREATED_AT + ", " + expires + ", " + max + ", " + count + ")";
    }

    private static void assertSqlState(Connection connection, String expected, String sql) {
        // Autocommit makes every negative assertion an independent transaction.
        SQLException failure = assertThrows(SQLException.class, () -> execute(connection, sql), sql);
        assertEquals(expected, failure.getSQLState(), sql);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        List<String> result = strings(connection, sql);
        assertEquals(1, result.size(), sql);
        return result.getFirst();
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        }
    }
}
