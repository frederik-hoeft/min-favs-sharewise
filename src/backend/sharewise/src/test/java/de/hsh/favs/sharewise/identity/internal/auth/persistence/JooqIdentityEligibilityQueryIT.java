package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.dbTime;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Sessions.SESSIONS;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Users.USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.verification.IdentityEligibilityQuery;
import de.hsh.favs.sharewise.identity.internal.verification.SessionValidityWindow;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real migrated PostgreSQL predicate tests, isolated by the shared rollback fixture. */
@Tag("integration")
class JooqIdentityEligibilityQueryIT extends IntegrationTestBase {
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    @Test
    void accountAndSessionMustExistMatchAndRemainEnabledAndUnrevoked() {
        runIntegrationTest(scope -> {
            JooqAccess access = scope.resolve(JooqAccess.class);
            IdentityEligibilityQuery query = scope.resolve(IdentityEligibilityQuery.class);
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext db = access.dsl();
                UUID user = insertUser(db);
                UUID other = insertUser(db);
                UUID session = insertSession(db, user, NOW.minusSeconds(1), NOW.plusSeconds(1), NOW.plusSeconds(2));
                assertTrue(query.findSessionValidityWindow(user, session).isPresent());
                assertTrue(query.findSessionValidityWindow(UUID.randomUUID(), session).isEmpty());
                assertTrue(query.findSessionValidityWindow(user, UUID.randomUUID()).isEmpty());
                assertTrue(query.findSessionValidityWindow(other, session).isEmpty());
                db.update(USERS).set(USERS.FAILED_LOGIN_ATTEMPTS, 5).set(USERS.LOCKED_UNTIL, dbTime(NOW.plusSeconds(60)))
                        .where(USERS.ID.eq(user)).execute();
                assertTrue(query.findSessionValidityWindow(user, session).isPresent());
                db.update(USERS).set(USERS.DISABLED_AT, dbTime(NOW)).where(USERS.ID.eq(user)).execute();
                assertTrue(query.findSessionValidityWindow(user, session).isEmpty());
                db.update(USERS).setNull(USERS.DISABLED_AT).where(USERS.ID.eq(user)).execute();
                db.update(SESSIONS).set(SESSIONS.REVOKED_AT, dbTime(NOW)).where(SESSIONS.ID.eq(session)).execute();
                assertTrue(query.findSessionValidityWindow(user, session).isEmpty());
                return TransactionOutcome.abstain(null);
            });
        });
    }

    @Test
    void projectionReturnsExactPersistedSessionIdAndDeadlinesWithoutTemporalFiltering() {
        runIntegrationTest(scope -> {
            JooqAccess access = scope.resolve(JooqAccess.class);
            IdentityEligibilityQuery query = scope.resolve(IdentityEligibilityQuery.class);
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext db = access.dsl();
                UUID user = insertUser(db);
                Instant created = NOW.plusNanos(123_456_000);
                Instant idle = created.plusSeconds(1);
                Instant absolute = created.plusSeconds(2);
                UUID session = insertSession(db, user, created, idle, absolute);
                assertEquals(Optional.of(new SessionValidityWindow(session, created, idle, absolute)),
                        query.findSessionValidityWindow(user, session));
                return TransactionOutcome.abstain(null);
            });
        });
    }

    private static UUID insertUser(DSLContext db) {
        String email = UUID.randomUUID() + "@example.org";
        return db.insertInto(USERS).columns(USERS.EMAIL, USERS.NORMALIZED_EMAIL, USERS.PASSWORD_HASH)
                .values(email, email, "test-verifier").returning(USERS.ID).fetchSingle().getId();
    }

    private static UUID insertSession(DSLContext db, UUID user, Instant created, Instant idle, Instant absolute) {
        return db.insertInto(SESSIONS).columns(SESSIONS.USER_ID, SESSIONS.CREATED_AT,
                SESSIONS.IDLE_EXPIRES_AT, SESSIONS.ABSOLUTE_EXPIRES_AT)
                .values(user, dbTime(created), dbTime(idle), dbTime(absolute))
                .returning(SESSIONS.ID).fetchSingle().getId();
    }
}
