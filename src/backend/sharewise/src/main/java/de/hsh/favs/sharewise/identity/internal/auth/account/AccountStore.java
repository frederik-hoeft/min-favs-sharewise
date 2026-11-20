package de.hsh.favs.sharewise.identity.internal.auth.account;

import java.util.Optional;
import java.util.UUID;

/** Account operations within the caller's active transaction segment. */
public interface AccountStore {

    /** Insert a new account, or return empty when the canonical email already exists. */
    Optional<AccountSnapshot> insert(EmailAddress email, String encodedPassword);

    /** Snapshot credentials for verification outside a row-locking transaction. */
    Optional<AccountSnapshot> findByEmail(EmailAddress email);

    /** Serialize credential decisions and lockout updates on this account row. */
    Optional<AccountSnapshot> findByEmailForUpdate(EmailAddress email);

    /** Update a previously locked account without overwriting a stale failure state. */
    boolean compareAndSetLoginFailures(UUID userId, LoginLockoutPolicy.State previous,
            LoginLockoutPolicy.State next);
}
