package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.AuthTables.*;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/** SQL ownership stays inside IAM; jOOQ facades never escape the active segment. */
@Repository
@Scope("operation")
final class JooqAccountStore implements AccountStore {

    private final JooqAccess database;

    JooqAccountStore(JooqAccess database) {
        this.database = database;
    }

    @Override
    public Optional<AccountSnapshot> insert(EmailAddress email, String encodedPassword) {
        Objects.requireNonNull(email, "email");
        if (Objects.requireNonNull(encodedPassword, "encodedPassword").isBlank()) {
            throw new IllegalArgumentException("Encoded password must not be blank");
        }
        DSLContext db = database.dsl();
        // The unique constraint is the arbiter, including under concurrent registrations.
        // ON CONFLICT avoids aborting a transaction for an expected duplicate email.
        return db.insertInto(USERS)
                .columns(EMAIL, NORMALIZED_EMAIL, PASSWORD_HASH)
                .values(email.display(), email.normalized(), encodedPassword)
                .onConflict(NORMALIZED_EMAIL).doNothing()
                .returning(USER_ID, EMAIL, NORMALIZED_EMAIL, PASSWORD_HASH, DISABLED_AT,
                        FAILED_ATTEMPTS, LOCKED_UNTIL, ACCOUNT_CREATED_AT)
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public Optional<AccountSnapshot> findByEmailForUpdate(EmailAddress email) {
        Objects.requireNonNull(email, "email");
        DSLContext db = database.dsl();
        return db.select(USER_ID, EMAIL, NORMALIZED_EMAIL, PASSWORD_HASH, DISABLED_AT,
                        FAILED_ATTEMPTS, LOCKED_UNTIL, ACCOUNT_CREATED_AT)
                .from(USERS)
                .where(NORMALIZED_EMAIL.eq(email.normalized()))
                .forUpdate()
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public boolean compareAndSetLoginFailures(UUID userId, LoginLockoutPolicy.State previous,
            LoginLockoutPolicy.State next) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        DSLContext db = database.dsl();
        return db.update(USERS)
                .set(FAILED_ATTEMPTS, next.failedAttempts())
                .set(LOCKED_UNTIL, dbTime(next.lockedUntil()))
                .where(USER_ID.eq(userId))
                .and(FAILED_ATTEMPTS.eq(previous.failedAttempts()))
                .and(LOCKED_UNTIL.isNotDistinctFrom(dbTime(previous.lockedUntil())))
                .execute() == 1;
    }

    private static AccountSnapshot account(Record row) {
        EmailAddress email = EmailAddress.parse(row.get(EMAIL));
        if (!email.normalized().equals(row.get(NORMALIZED_EMAIL))) {
            throw new IllegalStateException("Stored IAM email canonicalization is inconsistent");
        }
        return new AccountSnapshot(row.get(USER_ID), email, row.get(PASSWORD_HASH),
                instant(row.get(ACCOUNT_CREATED_AT)), instant(row.get(DISABLED_AT)),
                new LoginLockoutPolicy.State(row.get(FAILED_ATTEMPTS), instant(row.get(LOCKED_UNTIL))));
    }
}
