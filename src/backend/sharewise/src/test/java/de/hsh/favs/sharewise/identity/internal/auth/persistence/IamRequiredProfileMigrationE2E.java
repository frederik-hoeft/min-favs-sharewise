package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the actual forward migration from the pre-P2.2f schema. */
@Tag("e2e")
class IamRequiredProfileMigrationE2E {
    private static final String PREDECESSOR = "202611251310";
    private static final String REQUIRED_NAMES = "202611271640";
    private static final UUID NAMED = UUID.fromString("019b0000-0000-7000-8000-0000aabbccdd");
    private static final UUID NULL_NAME = UUID.fromString("019b0000-0000-7000-8000-0000deadbeef");
    private static final UUID MISSING = UUID.fromString("019b0000-0000-7000-8000-0001deadbeef");
    private static final UUID DISABLED = UUID.fromString("019b0000-0000-7000-8000-000011223344");
    private static final UUID DISABLED_NULL = UUID.fromString("019b0000-0000-7000-8000-000055667788");
    private static final UUID EXISTING_DEFAULT = UUID.fromString("019b0000-0000-7000-8000-000099aabbcc");
    private static final String ORIGINAL_NAME = "  🌍 A\u0308lice  ";

    @Test
    void upgradesEveryAccountWithoutChangingExistingNamesOrAuthenticationState() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            migrateTo(database, PREDECESSOR).migrate();
            try (Connection app = database.connectAsApplication()) {
                seedAccount(app, NAMED, "named@example.com", false);
                seedAccount(app, NULL_NAME, "private-local-part@example.com", false);
                seedAccount(app, MISSING, "missing@example.com", false);
                seedAccount(app, DISABLED, "disabled@example.com", true);
                seedAccount(app, DISABLED_NULL, "disabled-null@example.com", true);
                seedAccount(app, EXISTING_DEFAULT, "existing-default@example.com", false);
                insertLegacyProfile(app, NAMED, ORIGINAL_NAME);
                insertLegacyProfile(app, NULL_NAME, null);
                insertLegacyProfile(app, DISABLED_NULL, null);
                insertLegacyProfile(app, EXISTING_DEFAULT, "User deadbeef");
                // The migration must not reinterpret a user's existing name, even if it
                // looks exactly like a generated fallback or collides with one.
                try (PreparedStatement insert = app.prepareStatement("""
                        INSERT INTO identity.sessions (id, user_id, created_at, idle_expires_at, absolute_expires_at)
                        VALUES ('019b0000-0000-7000-8000-000000000001', ?,
                                '2026-11-25T10:00:00Z', '2026-11-25T11:00:00Z', '2026-11-26T10:00:00Z')
                        """)) {
                    insert.setObject(1, NAMED);
                    assertEquals(1, insert.executeUpdate());
                }
                execute(app, """
                    INSERT INTO identity.refresh_tokens (session_id, verifier_hash, issued_at)
                    VALUES ('019b0000-0000-7000-8000-000000000001', decode(repeat('ab', 32), 'hex'),
                            '2026-11-25T10:00:00Z')
                    """);
                List<String> accounts = rows(app, "SELECT to_jsonb(u)::text FROM identity.users u ORDER BY id");
                List<String> sessions = rows(app, "SELECT to_jsonb(s)::text FROM identity.sessions s ORDER BY id");
                List<String> tokens = rows(app, "SELECT to_jsonb(t)::text FROM identity.refresh_tokens t ORDER BY id");

                Flyway upgraded = migrateTo(database, REQUIRED_NAMES);
                assertEquals(1, upgraded.migrate().migrationsExecuted);
                upgraded.validate();

                assertEquals(ORIGINAL_NAME, displayName(app, NAMED));
                assertEquals("User deadbeef", displayName(app, NULL_NAME));
                assertEquals("User deadbeef", displayName(app, MISSING));
                assertEquals("User 11223344", displayName(app, DISABLED));
                assertEquals("User 55667788", displayName(app, DISABLED_NULL));
                assertEquals("User deadbeef", displayName(app, EXISTING_DEFAULT));
                assertEquals(List.of("6"), rows(app, "SELECT count(*)::text FROM identity.user_profiles"));
                assertEquals(List.of("0"), rows(app, """
                    SELECT count(*)::text FROM identity.users u
                    LEFT JOIN identity.user_profiles p ON p.user_id = u.id
                    WHERE p.display_name IS NULL
                    """));
                assertEquals(List.of("NO"), nullability(app));
                assertEquals(accounts, rows(app, "SELECT to_jsonb(u)::text FROM identity.users u ORDER BY id"));
                assertEquals(sessions, rows(app, "SELECT to_jsonb(s)::text FROM identity.sessions s ORDER BY id"));
                assertEquals(tokens, rows(app, "SELECT to_jsonb(t)::text FROM identity.refresh_tokens t ORDER BY id"));
            }
        }
    }

    @Test
    void failedBackfillRollsBackEarlierUpdatesAndCanBeRetried() throws SQLException {
        try (PostgresTestDatabase database = PostgresTestDatabase.createBootstrapped()) {
            migrateTo(database, PREDECESSOR).migrate();
            try (Connection app = database.connectAsApplication(); Connection migrator = database.connectAsMigrator()) {
                seedAccount(app, NULL_NAME, "null@example.com", false);
                seedAccount(app, DISABLED, "missing@example.com", true);
                insertLegacyProfile(app, NULL_NAME, null);
                // Force the missing-row INSERT to fail after the NULL-row UPDATE.
                // This constraint belongs only to this disposable database.
                execute(migrator, """
                    ALTER TABLE identity.user_profiles ADD CONSTRAINT test_reject_backfill
                        CHECK (display_name IS DISTINCT FROM 'User 11223344')
                    """);
                Flyway upgrade = migrateTo(database, REQUIRED_NAMES);
                FlywayException failure = assertThrows(FlywayException.class, upgrade::migrate);
                assertTrue(failure.getMessage().contains("test_reject_backfill"), failure.getMessage());
                assertNull(displayName(app, NULL_NAME));
                assertEquals(List.of("1"), rows(app, "SELECT count(*)::text FROM identity.user_profiles"));
                assertEquals(List.of("YES"), nullability(app));
                assertFalse(Arrays.stream(upgrade.info().applied())
                    .anyMatch(info -> info.getVersion() != null && REQUIRED_NAMES.equals(info.getVersion().getVersion())));

                execute(migrator, "ALTER TABLE identity.user_profiles DROP CONSTRAINT test_reject_backfill");
                assertEquals(1, upgrade.migrate().migrationsExecuted);
                upgrade.validate();
                assertEquals("User deadbeef", displayName(app, NULL_NAME));
                assertEquals("User 11223344", displayName(app, DISABLED));
                assertEquals(List.of("NO"), nullability(app));
            }
        }
    }

    private static Flyway migrateTo(PostgresTestDatabase database, String version) {
        return Flyway.configure()
            .dataSource(database.jdbcUrl(), PostgresTestDatabase.MIGRATOR_USER,
                database.connectionSettings().migratorPassword())
            .locations("classpath:db/migration").defaultSchema("flyway").schemas("flyway")
            .outOfOrder(false).target(version).load();
    }

    private static void seedAccount(Connection app, UUID id, String email, boolean disabled) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement("""
                INSERT INTO identity.users (id, email, normalized_email, password_hash, disabled_at)
                VALUES (?, ?, ?, 'legacy-verifier', CASE WHEN ? THEN now() ELSE NULL END)
                """)) {
            insert.setObject(1, id);
            insert.setString(2, email);
            insert.setString(3, email);
            insert.setBoolean(4, disabled);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertLegacyProfile(Connection app, UUID id, String name) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO identity.user_profiles (user_id, display_name) VALUES (?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, name);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static String displayName(Connection app, UUID id) throws SQLException {
        try (PreparedStatement query = app.prepareStatement(
                "SELECT display_name FROM identity.user_profiles WHERE user_id = ?")) {
            query.setObject(1, id);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                String name = result.getString(1);
                assertFalse(result.next());
                return name;
            }
        }
    }

    private static List<String> nullability(Connection app) throws SQLException {
        return rows(app, """
            SELECT is_nullable FROM information_schema.columns
            WHERE table_schema = 'identity' AND table_name = 'user_profiles' AND column_name = 'display_name'
            """);
    }

    private static List<String> rows(Connection app, String query) throws SQLException {
        try (Statement statement = app.createStatement(); ResultSet result = statement.executeQuery(query)) {
            List<String> values = new ArrayList<>();
            while (result.next()) values.add(result.getString(1));
            return List.copyOf(values);
        }
    }

    private static void execute(Connection app, String sql) throws SQLException {
        try (Statement statement = app.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
