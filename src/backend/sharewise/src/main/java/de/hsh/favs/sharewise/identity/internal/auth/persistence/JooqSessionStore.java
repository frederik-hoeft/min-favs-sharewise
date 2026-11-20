package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.AuthTables.*;

import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

@Repository
@Scope("operation")
final class JooqSessionStore implements SessionStore {

    private final JooqAccess database;

    JooqSessionStore(JooqAccess database) {
        this.database = database;
    }

    @Override
    public LoginSession insert(UUID userId, SessionPolicy.Deadlines deadlines) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(deadlines, "deadlines");
        DSLContext db = database.dsl();
        Record row = db.insertInto(SESSIONS)
                .columns(SESSION_USER_ID, SESSION_CREATED_AT, IDLE_EXPIRES_AT, ABSOLUTE_EXPIRES_AT)
                .values(userId, dbTime(deadlines.createdAt()), dbTime(deadlines.idleExpiresAt()),
                        dbTime(deadlines.absoluteExpiresAt()))
                .returning(SESSION_ID, SESSION_USER_ID, SESSION_CREATED_AT, IDLE_EXPIRES_AT,
                        ABSOLUTE_EXPIRES_AT, REVOKED_AT)
                .fetchSingle();
        SessionPolicy.Deadlines persistedDeadlines = new SessionPolicy.Deadlines(
                instant(row.get(SESSION_CREATED_AT)), instant(row.get(IDLE_EXPIRES_AT)),
                instant(row.get(ABSOLUTE_EXPIRES_AT)));
        return new LoginSession(row.get(SESSION_ID), row.get(SESSION_USER_ID),
                persistedDeadlines, instant(row.get(REVOKED_AT)));
    }
}
