package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.dbTime;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.instant;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.RefreshTokens.REFRESH_TOKENS;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Sessions.SESSIONS;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Users.USERS;

import de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.records.RefreshTokensRecord;
import de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.records.SessionsRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * IAM-private PostgreSQL lifecycle adapter. All calls participate in the caller's active
 * transaction segment; the application service owns the lock order and final decision.
 */
@Repository
@Scope("operation")
class JooqSessionLifecycleStore implements SessionLifecycleStore {

    private final JooqAccess database;

    JooqSessionLifecycleStore(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<UUID> findSessionId(UUID tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return database.dsl().select(REFRESH_TOKENS.SESSION_ID)
                .from(REFRESH_TOKENS)
                .where(REFRESH_TOKENS.ID.eq(tokenId))
                .fetchOptional(REFRESH_TOKENS.SESSION_ID);
    }

    @Override
    public Optional<LockedSession> lockSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        DSLContext db = database.dsl();
        // Always acquire the session lock before reading mutable token history or writing.
        // A competing rotation/logout must finish before this transaction re-reads the token.
        Optional<SessionsRecord> locked = db.selectFrom(SESSIONS)
                .where(SESSIONS.ID.eq(sessionId))
                .forUpdate()
                .fetchOptional();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        SessionsRecord row = locked.orElseThrow();
        // The users FK guarantees the account exists; do not lock the user row.
        OffsetDateTime disabledAt = db.select(USERS.DISABLED_AT)
                .from(USERS)
                .where(USERS.ID.eq(row.getUserId()))
                .fetchSingle(USERS.DISABLED_AT);
        return Optional.of(new LockedSession(JooqSessionStore.session(row), disabledAt != null));
    }

    @Override
    public Optional<RefreshTokenRecord> findToken(UUID tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return database.dsl().selectFrom(REFRESH_TOKENS)
                .where(REFRESH_TOKENS.ID.eq(tokenId))
                .fetchOptional(JooqSessionLifecycleStore::token);
    }

    @Override
    public void consume(UUID tokenId, Instant consumedAt) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(consumedAt, "consumedAt");
        int updated = database.dsl().update(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.CONSUMED_AT, dbTime(consumedAt))
                .where(REFRESH_TOKENS.ID.eq(tokenId))
                .and(REFRESH_TOKENS.CONSUMED_AT.isNull())
                .execute();
        requireOne(updated, "Refresh generation was not active when consumed");
    }

    @Override
    public void extendIdle(UUID sessionId, Instant idleExpiresAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        int updated = database.dsl().update(SESSIONS)
                .set(SESSIONS.IDLE_EXPIRES_AT, dbTime(idleExpiresAt))
                .where(SESSIONS.ID.eq(sessionId))
                .and(SESSIONS.REVOKED_AT.isNull())
                .and(SESSIONS.IDLE_EXPIRES_AT.le(dbTime(idleExpiresAt)))
                .and(SESSIONS.ABSOLUTE_EXPIRES_AT.ge(dbTime(idleExpiresAt)))
                .execute();
        requireOne(updated, "Locked session could not extend its idle deadline");
    }

    @Override
    public void revoke(UUID sessionId, Instant revokedAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(revokedAt, "revokedAt");
        // Retain all token generations and the original revocation timestamp for replay forensics.
        int updated = database.dsl().update(SESSIONS)
                .set(SESSIONS.REVOKED_AT, DSL.coalesce(SESSIONS.REVOKED_AT, dbTime(revokedAt)))
                .where(SESSIONS.ID.eq(sessionId))
                .execute();
        requireOne(updated, "Locked session disappeared during revocation");
    }

    private static RefreshTokenRecord token(RefreshTokensRecord row) {
        return new RefreshTokenRecord(row.getId(), row.getSessionId(), row.getVerifierHash(),
                instant(row.getIssuedAt()), instant(row.getConsumedAt()));
    }

    private static void requireOne(int affected, String failure) {
        if (affected != 1) {
            throw new IllegalStateException(failure);
        }
    }
}
