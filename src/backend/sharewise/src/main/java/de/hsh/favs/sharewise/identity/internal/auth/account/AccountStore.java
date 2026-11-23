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

    /** Snapshot the caller's credentials before expensive password verification. */
    Optional<AccountSnapshot> findById(UUID userId);

    /** Lock the account row before a credential change or its committed failure counter. */
    Optional<AccountSnapshot> findByIdForUpdate(UUID userId);

    /** Replace the locked account verifier and clear failure state; fail on stale state. */
    void replacePassword(UUID userId, String previousVerifier, String newVerifier);

    /** Update a previously locked account without overwriting a stale failure state. */
    boolean compareAndSetLoginFailures(UUID userId, LoginLockoutPolicy.State previous,
            LoginLockoutPolicy.State next);
}
