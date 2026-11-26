package de.hsh.favs.sharewise.groups.internal.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.postgres.MigrationScripts;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies forward-only deployment of the Group migrations over existing data. */
@Tag("e2e")
class GroupMigrationE2E {

    private static final String REVISION_MIGRATION = "202611261000";
    private static final String FINANCE_BACKFILL =
        "db/migration/platform/V202611261930__platform_bootstrap_finance_from_groups.sql";

    @Test
    void upgradesMainWithoutChangingExistingIamOrFinanceData() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            FluentConfiguration configuration = migrations(database);
            Flyway baseline = configuration.target("202611241838").load();
            baseline.migrate();
            // Exact historical main baseline, not an inventory of the latest schema.
            assertEquals(List.of("202611131000", "202611201400", "202611221630", "202611241838"),
                Arrays.stream(baseline.info().applied()).filter(info -> info.getVersion() != null)
                    .map(info -> info.getVersion().getVersion()).toList());

            try (Connection app = database.connectAsApplication(); Statement sql = app.createStatement()) {
                assertEquals("true", scalar(sql, "SELECT (to_regclass('groups.groups') IS NULL)::text"));
                String userId = scalar(sql, """
                    INSERT INTO identity.users (email, normalized_email, password_hash)
                    VALUES ('existing@example.com', 'existing@example.com', 'existing-hash') RETURNING id::text
                    """);
                sql.executeUpdate("INSERT INTO identity.user_profiles (user_id, display_name) VALUES ('" + userId + "', 'Existing')");
                String groupId = "10000000-0000-0000-0000-000000000001";
                sql.executeUpdate("INSERT INTO finance.groups (id, state, current_revision) VALUES ('" + groupId + "', 'open', 7)");
                sql.executeUpdate("INSERT INTO finance.actors (group_id, user_id, deleted, upstream_revision) VALUES ('"
                    + groupId + "', '" + userId + "', false, 3)");

                Flyway upgraded = configuration.target(MigrationVersion.LATEST).load();
                upgraded.migrate();
                upgraded.validate();
                assertEquals("Existing", scalar(sql, "SELECT display_name FROM identity.user_profiles WHERE user_id = '" + userId + "'"));
                assertEquals("existing-hash", scalar(sql, "SELECT password_hash FROM identity.users WHERE id = '" + userId + "'"));
                assertEquals("open|7", scalar(sql, "SELECT state::text || '|' || current_revision::text FROM finance.groups WHERE id = '" + groupId + "'"));
                assertEquals("false|3", scalar(sql, "SELECT deleted::text || '|' || upstream_revision::text FROM finance.actors WHERE group_id = '"
                    + groupId + "' AND user_id = '" + userId + "'"));

                // The P2.4a bootstrap migration projects accounts that existed before the Group listener.
                assertEquals("1", scalar(sql, "SELECT count(*)::text FROM groups.users WHERE id = '" + userId + "'"));
                sql.executeUpdate("INSERT INTO groups.groups (id, name) VALUES ('" + groupId + "', 'Trip')");
                sql.executeUpdate("INSERT INTO groups.group_members (group_id, user_id, role) VALUES ('"
                    + groupId + "', '" + userId + "', 'admin')");
                assertEquals("0", scalar(sql, "SELECT version::text FROM groups.groups WHERE id = '" + groupId + "'"));
                assertEquals("admin", scalar(sql, "SELECT role::text FROM groups.group_members WHERE group_id = '"
                    + groupId + "' AND user_id = '" + userId + "'"));
            }
        }
    }

    /** DLV-R01: the revision column gives groups that existed before it revision 1. */
    @Test
    void existingGroupsStartAtMembershipRevisionOne() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            FluentConfiguration configuration = migrations(database);
            configuration.target("202611251410").load().migrate();

            try (Connection app = database.connectAsApplication(); Statement sql = app.createStatement()) {
                assertEquals("true", scalar(sql, """
                    SELECT (NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'groups'
                        AND table_name = 'groups' AND column_name = 'membership_revision'))::text"""));
                String groupId = scalar(sql, "INSERT INTO groups.groups (name) VALUES ('Existing') RETURNING id::text");

                Flyway upgraded = configuration.target(MigrationVersion.LATEST).load();
                upgraded.migrate();
                upgraded.validate();
                assertEquals("1", scalar(sql, "SELECT membership_revision::text FROM groups.groups WHERE id = '" + groupId + "'"));
                assertEquals("groups.bignatural1:NO:1", scalar(sql, """
                    SELECT domain_schema || '.' || domain_name || ':' || is_nullable || ':' || column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'groups' AND table_name = 'groups' AND column_name = 'membership_revision'"""));
            }
        }
    }

    /**
     * DLV-T17, DLV-R15: the backfill gives every pre-existing group an open Finance root at
     * revision zero and every current member an active actor at the group's membership revision.
     * Existing Finance rows are kept, and re-executing the script changes nothing.
     */
    @Test
    void backfillInitializesFinanceForPreExistingGroupsIdempotently() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            FluentConfiguration configuration = migrations(database);
            Flyway beforeBackfill = configuration.target(REVISION_MIGRATION).load();
            beforeBackfill.migrate();
            assertEquals(MigrationVersion.fromVersion(REVISION_MIGRATION), beforeBackfill.info().current().getVersion());

            String trip = "10000000-0000-0000-0000-000000000001";
            String flat = "10000000-0000-0000-0000-000000000002";
            String club = "10000000-0000-0000-0000-000000000003";
            String alice = "20000000-0000-0000-0000-000000000001";
            String bob = "20000000-0000-0000-0000-000000000002";
            String carol = "20000000-0000-0000-0000-000000000003";
            String dave = "20000000-0000-0000-0000-000000000004";
            try (Connection app = database.connectAsApplication(); Statement sql = app.createStatement()) {
                sql.executeUpdate("INSERT INTO groups.users (id) VALUES ('" + alice + "'), ('" + bob + "'), ('"
                    + carol + "'), ('" + dave + "')");
                // Two members at the default revision; a group whose revision already advanced; and a
                // group Finance already knows, whose rows the backfill must not touch.
                sql.executeUpdate("INSERT INTO groups.groups (id, name, membership_revision) VALUES ('" + trip
                    + "', 'Trip', 1), ('" + flat + "', 'Flat', 3), ('" + club + "', 'Club', 2)");
                sql.executeUpdate("INSERT INTO groups.group_members (group_id, user_id, role) VALUES ('" + trip
                    + "', '" + alice + "', 'admin'), ('" + trip + "', '" + bob + "', 'member'), ('" + flat + "', '"
                    + carol + "', 'admin'), ('" + club + "', '" + dave + "', 'admin')");
                sql.executeUpdate("INSERT INTO finance.groups (id, state, current_revision, created_at) VALUES ('"
                    + club + "', 'closed', 7, '2026-01-01T00:00:00Z')");
                sql.executeUpdate("INSERT INTO finance.actors (group_id, user_id, deleted, upstream_revision,"
                    + " created_at, updated_at) VALUES ('" + club + "', '" + dave + "', true, 2,"
                    + " '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
            }

            Flyway upgraded = configuration.target(MigrationVersion.LATEST).load();
            upgraded.migrate();
            upgraded.validate();

            try (Connection app = database.connectAsApplication(); Statement sql = app.createStatement()) {
                assertEquals(List.of(trip + "|open|0", flat + "|open|0", club + "|closed|7"), strings(sql,
                    "SELECT id || '|' || state || '|' || current_revision FROM finance.groups ORDER BY id"));
                assertEquals(List.of(trip + "|" + alice + "|false|1", trip + "|" + bob + "|false|1",
                        flat + "|" + carol + "|false|3", club + "|" + dave + "|true|2"),
                    strings(sql, "SELECT group_id || '|' || user_id || '|' || deleted || '|' || upstream_revision"
                        + " FROM finance.actors ORDER BY group_id, user_id"));
                List<String> backfilled = financeRows(sql);

                MigrationScripts.executeAsMigrator(database, FINANCE_BACKFILL);
                assertEquals(backfilled, financeRows(sql), "re-execution changes nothing");
            }
        }
    }

    /** Every Finance root and actor row with all columns, including creation and update times. */
    private static List<String> financeRows(Statement sql) throws SQLException {
        List<String> rows = new ArrayList<>(strings(sql, "SELECT g::text FROM finance.groups g ORDER BY id"));
        rows.addAll(strings(sql, "SELECT a::text FROM finance.actors a ORDER BY group_id, user_id"));
        return rows;
    }

    private static FluentConfiguration migrations(PostgresTestDatabase database) {
        return Flyway.configure()
            .dataSource(database.jdbcUrl(), PostgresTestDatabase.MIGRATOR_USER,
                database.connectionSettings().migratorPassword())
            .locations("classpath:db/migration").defaultSchema("flyway").schemas("flyway")
            .outOfOrder(false);
    }

    private static List<String> strings(Statement sql, String query) throws SQLException {
        try (ResultSet rows = sql.executeQuery(query)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static String scalar(Statement sql, String query) throws SQLException {
        try (ResultSet rows = sql.executeQuery(query)) {
            assertTrue(rows.next(), query);
            String value = rows.getString(1);
            assertFalse(rows.next(), query);
            return value;
        }
    }
}
