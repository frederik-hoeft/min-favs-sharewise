package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.RefreshTokens.REFRESH_TOKENS;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Sessions.SESSIONS;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Users.USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.Domains;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/** Contract checks for the IAM metadata generated from a migrated PostgreSQL schema. */
class GeneratedIamSchemaTests {

    @Test
    void preservesIamSchemaAndPostgresDomainTypes() {
        assertEquals("identity", USERS.getSchema().getName());
        assertEquals("identity", SESSIONS.getSchema().getName());
        assertEquals("identity", REFRESH_TOKENS.getSchema().getName());

        assertEquals(Domains.NATURAL0.getQualifiedName(),
                USERS.FAILED_LOGIN_ATTEMPTS.getDataType().getDomain().getQualifiedName());
        assertEquals(Integer.class, USERS.FAILED_LOGIN_ATTEMPTS.getType());
        assertEquals(OffsetDateTime.class, SESSIONS.IDLE_EXPIRES_AT.getType());
        assertEquals(byte[].class, REFRESH_TOKENS.VERIFIER_HASH.getType());
    }

    @Test
    void rendersQualifiedIamTableAndColumnReferences() {
        DSLContext db = DSL.using(SQLDialect.POSTGRES);
        String sql = db.select(USERS.ID, SESSIONS.USER_ID)
                .from(USERS)
                .join(SESSIONS).on(SESSIONS.USER_ID.eq(USERS.ID))
                .getSQL();
        assertTrue(sql.contains("\"identity\".\"users\""), sql);
        assertTrue(sql.contains("\"identity\".\"sessions\""), sql);
    }
}
