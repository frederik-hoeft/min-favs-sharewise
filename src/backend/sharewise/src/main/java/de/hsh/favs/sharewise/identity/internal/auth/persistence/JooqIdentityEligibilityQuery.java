package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.dbTime;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Sessions.SESSIONS;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Users.USERS;

import de.hsh.favs.sharewise.identity.internal.verification.EligibilityQueryUnavailableException;
import de.hsh.favs.sharewise.identity.internal.verification.IdentityEligibilityQuery;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/** A point-in-time projection of IAM-owned account and session state. */
@Repository
@Scope("operation")
class JooqIdentityEligibilityQuery implements IdentityEligibilityQuery {

    private final JooqAccess database;

    JooqIdentityEligibilityQuery(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public boolean isEligible(UUID userId, UUID sessionId, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        // PostgreSQL timestamptz has microsecond precision. Floor rather than letting JDBC
        // round forward: both <= creation and > expiration retain their exact meaning.
        OffsetDateTime evaluationTime = dbTime(Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS));
        DSLContext db = database.dsl();
        try {
            return db.fetchExists(db.selectOne().from(SESSIONS)
                    .join(USERS).on(USERS.ID.eq(SESSIONS.USER_ID))
                    .where(USERS.ID.eq(userId))
                    .and(USERS.DISABLED_AT.isNull())
                    .and(SESSIONS.ID.eq(sessionId))
                    .and(SESSIONS.CREATED_AT.le(evaluationTime))
                    .and(SESSIONS.REVOKED_AT.isNull())
                    .and(SESSIONS.IDLE_EXPIRES_AT.gt(evaluationTime))
                    .and(SESSIONS.ABSOLUTE_EXPIRES_AT.gt(evaluationTime)));
        } catch (org.jooq.exception.DataAccessException | org.springframework.dao.DataAccessException failure) {
            // Boot's jOOQ execute listener may translate the JDBC exception to a Spring exception.
            // Only recognized operational SQL states are availability failures. The service still
            // requires a successful physical rollback before returning a typed outcome.
            if (isUnavailable(failure)) {
                throw new EligibilityQueryUnavailableException(failure);
            }
            throw failure;
        }
    }

    private static boolean isUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("57014")
                        || state.equals("55P03") || state.equals("57P01")
                        || state.equals("57P02") || state.equals("57P03"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
