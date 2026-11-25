package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.USERS;

import de.hsh.favs.sharewise.groups.internal.application.access.GroupUserRegistry;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/** {@code groups.users}: existence checks and idempotent provisioning of supplied IAM user IDs. */
@Repository
@Scope("operation")
class JooqGroupUserRegistry implements GroupUserRegistry {

    private final JooqAccess database;

    JooqGroupUserRegistry(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public boolean isKnown(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return database.dsl().fetchExists(USERS, USERS.ID.eq(userId));
    }

    @Override
    public void provision(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        database.dsl().insertInto(USERS).set(USERS.ID, userId).onConflictDoNothing().execute();
    }
}
