package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.dbTime;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.RefreshTokens.REFRESH_TOKENS;

import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

@Repository
@Scope("operation")
final class JooqRefreshTokenStore implements RefreshTokenStore {

    private final JooqAccess database;

    JooqRefreshTokenStore(JooqAccess database) {
        this.database = database;
    }

    @Override
    public UUID newTokenId() {
        DSLContext db = database.dsl();
        return db.select(DSL.function("uuidv7", UUID.class)).fetchSingle().value1();
    }

    @Override
    public void insert(RefreshTokenRecord record) {
        Objects.requireNonNull(record, "record");
        DSLContext db = database.dsl();
        int inserted = db.insertInto(REFRESH_TOKENS)
                .columns(REFRESH_TOKENS.ID, REFRESH_TOKENS.SESSION_ID,
                        REFRESH_TOKENS.VERIFIER_HASH, REFRESH_TOKENS.ISSUED_AT,
                        REFRESH_TOKENS.CONSUMED_AT)
                .values(record.id(), record.sessionId(), record.verifierHash(),
                        dbTime(record.issuedAt()), dbTime(record.consumedAt()))
                .execute();
        if (inserted != 1) {
            throw new IllegalStateException("Initial refresh-token insert did not affect one row");
        }
    }
}
