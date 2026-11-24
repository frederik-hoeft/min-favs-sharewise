package de.hsh.favs.sharewise.finance.internal.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Finance's physical schema contract, independent of generated jOOQ metadata or Spring startup. */
@Tag("e2e")
class FinanceSchemaE2E {

    // Fixed, test-owned SQL literals; no external input. Each test owns a disposable database.
    private static final String GROUP = "'10000000-0000-0000-0000-000000000001'";
    private static final String OTHER_GROUP = "'10000000-0000-0000-0000-000000000002'";
    private static final String USER = "'20000000-0000-0000-0000-000000000001'";
    private static final String OTHER_USER = "'20000000-0000-0000-0000-000000000002'";
    private static final String ROOT_WHERE = " WHERE id = " + GROUP;
    private static final String ACTOR_WHERE = " WHERE group_id = " + GROUP + " AND user_id = " + USER;
    private static final String ACTOR_COLUMNS = "INSERT INTO finance.actors (group_id, user_id, deleted, upstream_revision) VALUES ";

    @Test
    void migratedSchemaPreservesRequiredColumnsLocalTypesKeysAndRuntimePrivileges() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection app = database.connectAsApplication();
             Connection migrator = database.connectAsMigrator()) {
            // Required column contracts, not a snapshot of every column in the latest schema.
            List<String> requiredColumns = List.of(
                "actors.group_id:pg_catalog.uuid:NO:false", "actors.user_id:pg_catalog.uuid:NO:false",
                "actors.deleted:pg_catalog.bool:NO:false", "actors.upstream_revision:finance.bignatural1:NO:false",
                "actors.created_at:pg_catalog.timestamptz:NO:true", "actors.updated_at:pg_catalog.timestamptz:NO:true",
                "groups.id:pg_catalog.uuid:NO:false", "groups.state:finance.group_state:NO:true",
                "groups.current_revision:finance.bignatural0:NO:true", "groups.created_at:pg_catalog.timestamptz:NO:true"
            );
            List<String> actualColumns = strings(app, """
                SELECT table_name || '.' || column_name || ':' || coalesce(domain_schema, udt_schema)
                    || '.' || coalesce(domain_name, udt_name) || ':' || is_nullable
                    || ':' || (column_default IS NOT NULL)::text
                FROM information_schema.columns
                WHERE table_schema = 'finance' AND table_name IN ('groups', 'actors')
                """);
            List<String> missingColumns = requiredColumns.stream().filter(column -> !actualColumns.contains(column)).toList();
            assertTrue(missingColumns.isEmpty(), () -> "Missing or changed required columns: " + missingColumns
                + "; actual: " + actualColumns);
            assertEquals(List.of("bignatural0:bigint", "bignatural1:bigint"), strings(migrator, """
                SELECT t.typname || ':' || format_type(t.typbasetype, NULL)
                FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = 'finance' AND t.typtype = 'd'
                    AND t.typname IN ('bignatural0', 'bignatural1') ORDER BY t.typname
                """));
            assertEquals(List.of("provisioning", "open", "closed", "archived"), strings(app, """
                SELECT enumlabel FROM pg_enum WHERE enumtypid = 'finance.group_state'::regtype ORDER BY enumsortorder
                """));
            assertEquals(List.of("actors.group_id", "actors.user_id", "groups.id"), strings(migrator, """
                SELECT k.table_name || '.' || k.column_name
                FROM information_schema.table_constraints c
                JOIN information_schema.key_column_usage k
                    USING (constraint_catalog, constraint_schema, constraint_name, table_catalog, table_schema, table_name)
                WHERE c.table_schema = 'finance' AND c.table_name IN ('groups', 'actors')
                    AND c.constraint_type = 'PRIMARY KEY'
                ORDER BY k.table_name, k.ordinal_position
                """));
            assertEquals(List.of("finance.actors.group_id->finance.groups.id:a:a"), strings(migrator, """
                SELECT source_ns.nspname || '.' || source.relname || '.' || source_column.attname || '->'
                    || target_ns.nspname || '.' || target.relname || '.' || target_column.attname
                    || ':' || c.confupdtype::text || ':' || c.confdeltype::text
                FROM pg_constraint c
                JOIN pg_class source ON source.oid = c.conrelid
                JOIN pg_namespace source_ns ON source_ns.oid = source.relnamespace
                JOIN pg_attribute source_column ON source_column.attrelid = source.oid
                    AND source_column.attnum = ANY(c.conkey)
                JOIN pg_class target ON target.oid = c.confrelid
                JOIN pg_namespace target_ns ON target_ns.oid = target.relnamespace
                JOIN pg_attribute target_column ON target_column.attrelid = target.oid
                    AND target_column.attnum = ANY(c.confkey)
                WHERE c.contype = 'f' AND c.conrelid = 'finance.actors'::regclass
                    AND c.confrelid = 'finance.groups'::regclass
                    AND source_column.attname = 'group_id' AND target_column.attname = 'id'
                    AND cardinality(c.conkey) = 1 AND cardinality(c.confkey) = 1
                """));
            // Finance-owned roots and actors must never depend on another context's tables.
            assertEquals("0", scalar(migrator, """
                SELECT count(*) FROM pg_constraint c
                JOIN pg_class target ON target.oid = c.confrelid
                WHERE c.contype = 'f'
                    AND c.conrelid IN ('finance.groups'::regclass, 'finance.actors'::regclass)
                    AND target.relnamespace <> 'finance'::regnamespace
                """));
            assertEquals(List.of("actors:BEFORE:UPDATE:ROW"), strings(migrator, """
                SELECT event_object_table || ':' || action_timing || ':' || event_manipulation || ':' || action_orientation
                FROM information_schema.triggers
                WHERE event_object_schema = 'finance' AND event_object_table = 'actors'
                    AND trigger_name = 'actors_set_updated_at'
                """));
            assertEquals(List.of("sharewise_migrator", "sharewise_migrator"), strings(migrator, """
                SELECT tableowner FROM pg_tables WHERE schemaname = 'finance'
                    AND tablename IN ('groups', 'actors') ORDER BY tablename
                """));
            assertEquals(List.of("sharewise_migrator", "sharewise_migrator", "sharewise_migrator"), strings(migrator, """
                SELECT pg_get_userbyid(typowner) FROM pg_type WHERE typnamespace = 'finance'::regnamespace
                AND typname IN ('group_state', 'bignatural0', 'bignatural1') ORDER BY typname
                """));
            assertEquals("sharewise_migrator", scalar(migrator, """
                SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = 'finance.set_actor_updated_at()'::regprocedure
                """));
            for (String table : List.of("groups", "actors")) {
                for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                    assertEquals("true", scalar(app, "SELECT has_table_privilege('finance." + table + "', '" + privilege + "')::text"));
                }
                assertEquals("false", scalar(app, "SELECT has_table_privilege('finance." + table + "', 'TRUNCATE')::text"));
            }
            for (String type : List.of("group_state", "bignatural0", "bignatural1")) {
                assertEquals("true", scalar(app, "SELECT has_type_privilege('finance." + type + "', 'USAGE')::text"));
            }
            assertEquals("0", scalar(migrator, """
                SELECT count(*) FROM pg_type t
                CROSS JOIN LATERAL aclexplode(coalesce(t.typacl, acldefault('T', t.typowner))) a
                WHERE t.typnamespace = 'finance'::regnamespace
                    AND t.typname IN ('group_state', 'bignatural0', 'bignatural1')
                    AND a.grantee = 0 AND a.privilege_type = 'USAGE'
                """));
            assertSqlState(app, "42501", "CREATE TABLE finance.forbidden (id uuid)");
            assertSqlState(app, "42501", "ALTER TABLE finance.groups ADD COLUMN forbidden text");
            assertSqlState(app, "42501", "ALTER DOMAIN finance.bignatural0 DROP CONSTRAINT finance_bignatural0_nonnegative");
        }
    }

    @Test
    void rootDefaultsAndRevisionBoundsAreEnforced() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection app = database.connectAsApplication()) {
            assertEquals("true", scalar(app, "INSERT INTO finance.groups (id) VALUES (" + GROUP + ")"
                + " RETURNING (state = 'provisioning' AND current_revision = 0 AND created_at = now())::text"));
            assertEquals("0", scalar(app, "SELECT count(*) FROM finance.actors WHERE group_id = " + GROUP));
            assertSqlState(app, "23502", "INSERT INTO finance.groups DEFAULT VALUES");
            assertSqlState(app, "23502", "INSERT INTO finance.groups (id) VALUES (NULL)");
            assertSqlState(app, "23505", "INSERT INTO finance.groups (id) VALUES (" + GROUP + ")");
            assertSqlState(app, "23514", "UPDATE finance.groups SET current_revision = -1" + ROOT_WHERE);
            assertSqlState(app, "23502", "UPDATE finance.groups SET current_revision = NULL" + ROOT_WHERE);
            assertSqlState(app, "23502", "UPDATE finance.groups SET state = NULL" + ROOT_WHERE);
            assertSqlState(app, "22P02", "UPDATE finance.groups SET state = 'unknown'" + ROOT_WHERE);
            assertSqlState(app, "23502", "UPDATE finance.groups SET created_at = NULL" + ROOT_WHERE);
            assertEquals("9223372036854775807", scalar(app,
                "UPDATE finance.groups SET current_revision = 9223372036854775807" + ROOT_WHERE + " RETURNING current_revision::text"));
            for (String state : List.of("open", "closed", "archived", "provisioning")) {
                assertEquals(state, scalar(app, "UPDATE finance.groups SET state = '" + state + "'" + ROOT_WHERE + " RETURNING state::text"));
            }
            assertEquals("0", scalar(app, "UPDATE finance.groups SET current_revision = 0" + ROOT_WHERE + " RETURNING current_revision::text"));
            execute(app, "DELETE FROM finance.groups WHERE id = " + GROUP);
            assertEquals("0", scalar(app, "SELECT count(*) FROM finance.groups" + ROOT_WHERE));
        }
    }

    @Test
    void actorIdentityAndPositiveRevisionAreEnforcedWithoutExternalRows() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection app = database.connectAsApplication()) {
            execute(app, "INSERT INTO finance.groups (id) VALUES (" + GROUP + "), (" + OTHER_GROUP + ")");
            assertEquals("true", scalar(app, ACTOR_COLUMNS + actor(GROUP, USER, "true", "1")
                + " RETURNING (deleted AND upstream_revision = 1 AND created_at = now() AND updated_at = now())::text"));
            execute(app, ACTOR_COLUMNS + actor(OTHER_GROUP, USER, "false", "9223372036854775807"));
            execute(app, ACTOR_COLUMNS + actor(GROUP, OTHER_USER, "false", "1"));
            assertSqlState(app, "23505", ACTOR_COLUMNS + actor(GROUP, USER, "false", "2"));
            assertSqlState(app, "23503", ACTOR_COLUMNS + actor("'30000000-0000-0000-0000-000000000001'", USER, "false", "1"));
            assertSqlState(app, "23502", ACTOR_COLUMNS + actor("NULL", USER, "false", "1"));
            assertSqlState(app, "23502", ACTOR_COLUMNS + actor(GROUP, "NULL", "false", "1"));
            assertSqlState(app, "23502", ACTOR_COLUMNS + actor(GROUP, USER, "NULL", "1"));
            assertSqlState(app, "23502", ACTOR_COLUMNS + actor(GROUP, USER, "false", "NULL"));
            assertSqlState(app, "23502", "INSERT INTO finance.actors (group_id, user_id, upstream_revision) VALUES (" + GROUP + "," + USER + ",1)");
            assertSqlState(app, "23502", "INSERT INTO finance.actors (group_id, user_id, deleted) VALUES (" + GROUP + "," + USER + ",false)");
            assertSqlState(app, "23514", "UPDATE finance.actors SET upstream_revision = 0" + ACTOR_WHERE);
            assertSqlState(app, "23514", "UPDATE finance.actors SET upstream_revision = -1" + ACTOR_WHERE);
            assertSqlState(app, "23502", "UPDATE finance.actors SET created_at = NULL" + ACTOR_WHERE);
            // The trigger supplies updated_at on UPDATE; NULL must still be rejected on INSERT.
            assertSqlState(app, "23502", "INSERT INTO finance.actors (group_id, user_id, deleted, upstream_revision, updated_at)"
                + " VALUES (" + OTHER_GROUP + "," + OTHER_USER + ",false,1,NULL)");
            assertSqlState(app, "23503", "DELETE FROM finance.groups WHERE id = " + GROUP);
            assertEquals("3", scalar(app, "SELECT count(*) FROM finance.actors WHERE group_id IN (" + GROUP + "," + OTHER_GROUP + ")"));
            assertEquals("2", scalar(app, "SELECT count(*) FROM finance.groups WHERE id IN (" + GROUP + "," + OTHER_GROUP + ")"));
            assertEquals("9223372036854775807", scalar(app,
                "SELECT upstream_revision::text FROM finance.actors WHERE group_id = " + OTHER_GROUP));
            // Schema-level DML is granted; future application operations own actor retention policy.
            execute(app, "DELETE FROM finance.actors WHERE group_id = " + OTHER_GROUP);
            assertEquals("2", scalar(app, "SELECT count(*) FROM finance.actors WHERE group_id IN (" + GROUP + "," + OTHER_GROUP + ")"));
        }
    }

    @Test
    void actorUpdateUsesTransactionTimestampAndPreservesCreationTimestamp() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createMigrated();
             Connection writer = database.connectAsApplication();
             Connection reader = database.connectAsApplication()) {
            execute(writer, "INSERT INTO finance.groups (id) VALUES (" + GROUP + ")");
            execute(writer, ACTOR_COLUMNS + actor(GROUP, USER, "false", "1"));
            String createdAt = scalar(reader, "SELECT created_at::text FROM finance.actors" + ACTOR_WHERE);
            writer.setAutoCommit(false);
            String updatedAt = scalar(writer, """
                UPDATE finance.actors SET deleted = true, upstream_revision = 2,
                    updated_at = '2000-01-01T00:00:00Z'
                """ + ACTOR_WHERE + " RETURNING updated_at::text");
            assertEquals(updatedAt, scalar(writer, "SELECT now()::text"));
            assertEquals("1", scalar(reader, "SELECT upstream_revision::text FROM finance.actors" + ACTOR_WHERE));
            writer.commit();
            assertEquals(updatedAt, scalar(reader, "SELECT updated_at::text FROM finance.actors" + ACTOR_WHERE));
            assertEquals(createdAt, scalar(reader, "SELECT created_at::text FROM finance.actors" + ACTOR_WHERE));
            assertEquals("true", scalar(reader, "SELECT (deleted AND upstream_revision = 2)::text FROM finance.actors" + ACTOR_WHERE));
        }
    }

    @Test
    void migrationUpgradesPriorIamSchemaWithoutChangingExistingData() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            FluentConfiguration configuration = Flyway.configure()
                .dataSource(database.jdbcUrl(), PostgresTestDatabase.MIGRATOR_USER,
                    database.connectionSettings().migratorPassword())
                .locations("classpath:db/migration").defaultSchema("flyway").schemas("flyway");
            configuration.target("202611221630").load().migrate();
            try (Connection app = database.connectAsApplication()) {
                String userId = scalar(app, """
                    INSERT INTO identity.users (email, normalized_email, password_hash)
                    VALUES ('existing@example.com', 'existing@example.com', 'existing-hash') RETURNING id::text
                    """);
                execute(app, "INSERT INTO identity.user_profiles (user_id, display_name) VALUES ('" + userId + "', 'Existing')");
                assertEquals("true", scalar(app, "SELECT (to_regclass('finance.groups') IS NULL)::text"));
                configuration.target(MigrationVersion.LATEST).load().migrate();
                assertEquals("Existing", scalar(app, "SELECT display_name FROM identity.user_profiles WHERE user_id = '" + userId + "'"));
                assertEquals("existing-hash", scalar(app, "SELECT password_hash FROM identity.users WHERE id = '" + userId + "'"));
                execute(app, "INSERT INTO finance.groups (id) VALUES (" + GROUP + ")");
                assertEquals("provisioning", scalar(app, "SELECT state::text FROM finance.groups" + ROOT_WHERE));
            }
            try (Connection migrator = database.connectAsMigrator()) {
                assertEquals("1", scalar(migrator, """
                    SELECT count(*) FROM flyway.flyway_schema_history
                    WHERE script LIKE '%__finance_groups_and_actors.sql' AND success
                    """));
            }
        }
    }

    private static String actor(String group, String user, String deleted, String revision) {
        return "(" + group + ", " + user + ", " + deleted + ", " + revision + ")";
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
