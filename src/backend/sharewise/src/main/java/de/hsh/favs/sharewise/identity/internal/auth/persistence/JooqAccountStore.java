package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.dbTime;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.DatabaseTime.instant;
import static de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.Users.USERS;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.persistence.generated.tables.records.UsersRecord;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/** SQL ownership stays inside IAM; jOOQ facades never escape the active segment. */
@Repository
@Scope("operation")
class JooqAccountStore implements AccountStore {

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
                .columns(USERS.EMAIL, USERS.NORMALIZED_EMAIL, USERS.PASSWORD_HASH)
                .values(email.display(), email.normalized(), encodedPassword)
                .onConflict(USERS.NORMALIZED_EMAIL).doNothing()
                .returning()
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public Optional<AccountSnapshot> findByEmail(EmailAddress email) {
        Objects.requireNonNull(email, "email");
        return database.dsl().selectFrom(USERS)
                .where(USERS.NORMALIZED_EMAIL.eq(email.normalized()))
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public Optional<AccountSnapshot> findByEmailForUpdate(EmailAddress email) {
        Objects.requireNonNull(email, "email");
        DSLContext db = database.dsl();
        return db.selectFrom(USERS)
                .where(USERS.NORMALIZED_EMAIL.eq(email.normalized()))
                .forUpdate()
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public Optional<AccountSnapshot> findById(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return database.dsl().selectFrom(USERS)
                .where(USERS.ID.eq(userId))
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public Optional<AccountSnapshot> findByIdForUpdate(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return database.dsl().selectFrom(USERS)
                .where(USERS.ID.eq(userId))
                .forUpdate()
                .fetchOptional(JooqAccountStore::account);
    }

    @Override
    public void replacePassword(UUID userId, String previousVerifier, String newVerifier) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(previousVerifier, "previousVerifier");
        if (Objects.requireNonNull(newVerifier, "newVerifier").isBlank()) {
            throw new IllegalArgumentException("New verifier must not be blank");
        }
        // The users row is already locked; the old verifier guards against a stale update.
        int updated = database.dsl().update(USERS)
                .set(USERS.PASSWORD_HASH, newVerifier)
                .set(USERS.FAILED_LOGIN_ATTEMPTS, 0)
                .setNull(USERS.LOCKED_UNTIL)
                .where(USERS.ID.eq(userId))
                .and(USERS.PASSWORD_HASH.eq(previousVerifier))
                .and(USERS.DISABLED_AT.isNull())
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("Locked IAM password verifier could not be updated");
        }
    }

    @Override
    public boolean compareAndSetLoginFailures(UUID userId, LoginLockoutPolicy.State previous,
            LoginLockoutPolicy.State next) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        DSLContext db = database.dsl();
        return db.update(USERS)
                .set(USERS.FAILED_LOGIN_ATTEMPTS, next.failedAttempts())
                .set(USERS.LOCKED_UNTIL, dbTime(next.lockedUntil()))
                .where(USERS.ID.eq(userId))
                .and(USERS.FAILED_LOGIN_ATTEMPTS.eq(previous.failedAttempts()))
                .and(USERS.LOCKED_UNTIL.isNotDistinctFrom(dbTime(previous.lockedUntil())))
                .execute() == 1;
    }

    private static AccountSnapshot account(UsersRecord row) {
        EmailAddress email = EmailAddress.parse(row.getEmail());
        if (!email.normalized().equals(row.getNormalizedEmail())) {
            throw new IllegalStateException("Stored IAM email canonicalization is inconsistent");
        }
        return new AccountSnapshot(row.getId(), email, row.getPasswordHash(),
                instant(row.getCreatedAt()), instant(row.getDisabledAt()),
                new LoginLockoutPolicy.State(row.getFailedLoginAttempts(), instant(row.getLockedUntil())));
    }
}
