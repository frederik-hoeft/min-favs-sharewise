package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.UserProfiles.USER_PROFILES;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Users.USERS;

import de.hsh.favs.sharewise.identity.internal.profile.AccountProfile;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileStore;
import de.hsh.favs.sharewise.identity.internal.profile.DisplayName;
import de.hsh.favs.sharewise.identity.internal.profile.PublicProfile;
import java.util.List;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/** IAM-owned SQL, including serialized profile updates. */
@Repository
@Scope("operation")
class JooqAccountProfileStore implements AccountProfileStore {

    private final JooqAccess database;

    JooqAccountProfileStore(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<AccountProfile> findActive(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return database.dsl().select(USERS.ID, USERS.EMAIL, USER_PROFILES.DISPLAY_NAME)
                .from(USERS)
                .leftJoin(USER_PROFILES).on(USER_PROFILES.USER_ID.eq(USERS.ID))
                .where(USERS.ID.eq(userId)).and(USERS.DISABLED_AT.isNull())
                .fetchOptional(row -> new AccountProfile(
                        row.get(USERS.ID), row.get(USERS.EMAIL), row.get(USER_PROFILES.DISPLAY_NAME)));
    }

    @Override
    public Optional<AccountProfile> setDisplayNameIfActive(UUID userId, DisplayName displayName) {
        Objects.requireNonNull(userId, "userId");
        DSLContext db = database.dsl();
        // Lock the authoritative account, preventing a concurrent disable from passing this check.
        Optional<String> email = db.select(USERS.EMAIL).from(USERS)
                .where(USERS.ID.eq(userId)).and(USERS.DISABLED_AT.isNull())
                .forUpdate().fetchOptional(USERS.EMAIL);
        if (email.isEmpty()) {
            return Optional.empty();
        }
        int written = db.update(USER_PROFILES)
                .set(USER_PROFILES.DISPLAY_NAME, displayName.value())
                .where(USER_PROFILES.USER_ID.eq(userId))
                .execute();
        if (written != 1) {
            throw new IllegalStateException("Required IAM profile update must affect exactly one row");
        }
        return Optional.of(new AccountProfile(userId, email.orElseThrow(), displayName.value()));
    }
    @Override
    public List<PublicProfile> lookup(List<UUID> userIds) {
        return database.dsl().select(USERS.ID, USER_PROFILES.DISPLAY_NAME)
                .from(USERS).leftJoin(USER_PROFILES).on(USER_PROFILES.USER_ID.eq(USERS.ID))
                .where(USERS.ID.in(userIds))
                .fetch(row -> new PublicProfile(row.get(USERS.ID), row.get(USER_PROFILES.DISPLAY_NAME)));
    }
}
