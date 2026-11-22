package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.dbTime;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.instant;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Sessions.SESSIONS;

import de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.records.SessionsRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

@Repository
@Scope("operation")
class JooqSessionStore implements SessionStore {

    private final JooqAccess database;

    JooqSessionStore(JooqAccess database) {
        this.database = database;
    }

    @Override
    public LoginSession insert(UUID userId, SessionPolicy.Deadlines deadlines) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(deadlines, "deadlines");
        DSLContext db = database.dsl();
        SessionsRecord row = db.insertInto(SESSIONS)
                .columns(SESSIONS.USER_ID, SESSIONS.CREATED_AT, SESSIONS.IDLE_EXPIRES_AT,
                        SESSIONS.ABSOLUTE_EXPIRES_AT)
                .values(userId, dbTime(deadlines.createdAt()), dbTime(deadlines.idleExpiresAt()),
                        dbTime(deadlines.absoluteExpiresAt()))
                .returning()
                .fetchSingle();
        SessionPolicy.Deadlines persistedDeadlines = new SessionPolicy.Deadlines(
                instant(row.getCreatedAt()), instant(row.getIdleExpiresAt()),
                instant(row.getAbsoluteExpiresAt()));
        return new LoginSession(row.getId(), row.getUserId(),
                persistedDeadlines, instant(row.getRevokedAt()));
    }
}
