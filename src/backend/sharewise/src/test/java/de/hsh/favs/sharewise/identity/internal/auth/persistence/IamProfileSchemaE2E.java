package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.Tables.USER_PROFILES;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.Keys;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Real PostgreSQL 18 contract for IAM's optional, one-to-one account profile. */
@Tag("e2e")
class IamProfileSchemaE2E extends E2ETestBase {

    @Test
    void migrationCreatesOptionalProfileWithRuntimeRolePrivileges() {
        runTest(fixture -> {
            try (Connection app = fixture.connectAsApplication()) {
                UUID userId = insertUser(app, "profile@example.com");
                assertEquals(0, countProfiles(app, userId), "creating an IAM user must not create a profile");

                try (PreparedStatement columns = app.prepareStatement("""
                        select column_name, data_type, is_nullable
                          from information_schema.columns
                         where table_schema = 'identity' and table_name = 'user_profiles'
                         order by ordinal_position
                        """); ResultSet rows = columns.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("user_id", rows.getString(1));
                    assertEquals("uuid", rows.getString(2));
                    assertEquals("NO", rows.getString(3));
                    assertTrue(rows.next());
                    assertEquals("display_name", rows.getString(1));
                    assertEquals("text", rows.getString(2));
                    assertEquals("YES", rows.getString(3));
                    assertFalse(rows.next());
                }

                for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                    assertTrue(hasTablePrivilege(app, privilege), "missing runtime " + privilege + " grant");
                }
                assertFalse(hasTablePrivilege(app, "TRUNCATE"));
                assertSqlState("42501", () -> execute(app, "alter table identity.user_profiles add column forbidden text"));

                insertProfile(app, userId, null);
                assertEquals(1, countProfiles(app, userId));
                assertNull(displayName(app, userId));
                assertEquals(Keys.USER_PROFILES_PKEY, USER_PROFILES.getPrimaryKey());
                assertEquals(List.of(Keys.USER_PROFILES__USER_PROFILES_USER_ID_FKEY),
                    USER_PROFILES.getReferences());
                assertEquals(userId, DSL.using(app, SQLDialect.POSTGRES)
                    .select(USER_PROFILES.USER_ID)
                    .from(USER_PROFILES)
                    .where(USER_PROFILES.USER_ID.eq(userId))
                    .fetchSingle(USER_PROFILES.USER_ID));
            }
        }, ProductionEntityScanConfiguration.class);
    }

    @Test
    void profileKeyEnforcesOneToOneOwnershipAndAccountForeignKey() {
        runTest(fixture -> {
            try (Connection app = fixture.connectAsApplication()) {
                UUID firstUser = insertUser(app, "first@example.com");
                UUID secondUser = insertUser(app, "second@example.com");
                insertProfile(app, firstUser, "First");

                assertSqlState("23505", () -> insertProfile(app, firstUser, "Duplicate"));
                assertSqlState("23503", () -> insertProfile(app,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), "Orphan"));
                assertSqlState("23503", () -> execute(app,
                        "delete from identity.users where id = '" + firstUser + "'"));
                assertEquals(1, countProfiles(app, firstUser));
                assertEquals(0, countProfiles(app, secondUser));
            }
        }, ProductionEntityScanConfiguration.class);
    }

    @Test
    void profileNameConstraintsPreserveValidUnicodeAndRejectInvalidStoredValues() {
        runTest(fixture -> {
            try (Connection app = fixture.connectAsApplication()) {
                UUID userId = insertUser(app, "unicode@example.com");
                insertProfile(app, userId, "🌍 Alice");
                assertEquals("🌍 Alice", displayName(app, userId));
                assertEquals("🌍 Alice", DSL.using(app, SQLDialect.POSTGRES)
                    .select(USER_PROFILES.DISPLAY_NAME)
                    .from(USER_PROFILES)
                    .where(USER_PROFILES.USER_ID.eq(userId))
                    .fetchSingle(USER_PROFILES.DISPLAY_NAME));

                updateDisplayName(app, userId, "x".repeat(80));
                assertEquals("x".repeat(80), displayName(app, userId));
                assertSqlState("23514", () -> updateDisplayName(app, userId, "x".repeat(81)));
                assertSqlState("23514", () -> updateDisplayName(app, userId, ""));
                assertSqlState("23514", () -> updateDisplayName(app, userId, " \t "));
                assertSqlState("23514", () -> updateDisplayName(app, userId, "Alice\nBob"));
                assertSqlState("23514", () -> updateDisplayName(app, userId, "Alice\u0085Bob"));
                assertSqlState("23514", () -> updateDisplayName(app, userId, "Alice\u2028Bob"));
                assertSqlState("23514", () -> updateDisplayName(app, userId, "Alice\u2029Bob"));
                assertEquals("x".repeat(80), displayName(app, userId));

                updateDisplayName(app, userId, null);
                assertNull(displayName(app, userId));
            }
        }, ProductionEntityScanConfiguration.class);
    }

    private static UUID insertUser(Connection app, String email) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement("""
                insert into identity.users (email, normalized_email, password_hash)
                values (?, ?, 'encoded') returning id
                """)) {
            insert.setString(1, email);
            insert.setString(2, email);
            try (ResultSet rows = insert.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static void insertProfile(Connection app, UUID userId, String displayName) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement(
                "insert into identity.user_profiles (user_id, display_name) values (?, ?)")) {
            insert.setObject(1, userId);
            insert.setString(2, displayName);
            insert.executeUpdate();
        }
    }

    private static void updateDisplayName(Connection app, UUID userId, String displayName) throws SQLException {
        try (PreparedStatement update = app.prepareStatement(
                "update identity.user_profiles set display_name = ? where user_id = ?")) {
            update.setString(1, displayName);
            update.setObject(2, userId);
            assertEquals(1, update.executeUpdate());
        }
    }

    private static int countProfiles(Connection app, UUID userId) throws SQLException {
        try (PreparedStatement query = app.prepareStatement(
                "select count(*) from identity.user_profiles where user_id = ?")) {
            query.setObject(1, userId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static String displayName(Connection app, UUID userId) throws SQLException {
        try (PreparedStatement query = app.prepareStatement(
                "select display_name from identity.user_profiles where user_id = ?")) {
            query.setObject(1, userId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static boolean hasTablePrivilege(Connection app, String privilege) throws SQLException {
        try (PreparedStatement query = app.prepareStatement(
                "select has_table_privilege(current_user, 'identity.user_profiles', ?)")) {
            query.setString(1, privilege);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getBoolean(1);
            }
        }
    }

    private static void assertSqlState(String expectedState, Executable statement) {
        SQLException failure = assertThrows(SQLException.class, statement);
        assertEquals(expectedState, failure.getSQLState());
    }

    private static void execute(Connection app, String sql) throws SQLException {
        try (Statement statement = app.createStatement()) {
            statement.execute(sql);
        }
    }
}
