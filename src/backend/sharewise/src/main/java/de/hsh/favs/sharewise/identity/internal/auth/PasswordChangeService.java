package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Changes the verifier and revokes every other session in one physical transaction.
 * Password verification and hashing happen before the user/session row locks are held.
 *
 * <p>Lock order: account FOR UPDATE, then caller session FOR UPDATE, then update other
 * sessions. Refresh/logout lock only their session and read the user without a user lock;
 * login locks the user before session creation. This ordering serializes login and
 * password changes without creating a reverse exclusive-lock path through refresh.
 */
public final class PasswordChangeService {

    private final IamSessionContext identity;
    private final Transactions transactions;
    private final AccountStore accounts;
    private final SessionLifecycleStore sessions;
    private final CredentialEncoder credentials;
    private final LoginLockoutPolicy lockouts;
    private final Clock clock;

    public PasswordChangeService(IamSessionContext identity, Transactions transactions,
            AccountStore accounts, SessionLifecycleStore sessions, CredentialEncoder credentials,
            LoginLockoutPolicy lockouts, Clock clock) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.lockouts = Objects.requireNonNull(lockouts, "lockouts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result<PasswordChangeStatus, PasswordChangeFailure> change(ChangePasswordCommand command) {
        Objects.requireNonNull(command, "command");
        VerifiedIamSession caller = identity.requireSession();
        Optional<AccountSnapshot> snapshot = transactions.execute(() ->
                TransactionOutcome.abstain(accounts.findById(caller.userId())));
        if (snapshot.isEmpty()) {
            return Result.err(PasswordChangeFailure.ACCOUNT_UNAVAILABLE);
        }
        AccountSnapshot original = snapshot.orElseThrow();
        // Expensive PBKDF2 operations must never run while holding PostgreSQL row locks.
        boolean matches = credentials.matches(command.currentPassword(), original.encodedPassword());
        String replacement = matches ? credentials.encode(command.newPassword()) : null;
        if (matches && (replacement == null || replacement.isBlank())) {
            throw new IllegalStateException("Credential encoder returned no verifier");
        }
        return transactions.execute(() -> {
            Optional<AccountSnapshot> locked = accounts.findByIdForUpdate(caller.userId());
            if (locked.isEmpty() || locked.orElseThrow().isDisabled()) {
                return denied(PasswordChangeFailure.ACCOUNT_UNAVAILABLE);
            }
            AccountSnapshot current = locked.orElseThrow();
            if (!current.encodedPassword().equals(original.encodedPassword())) {
                return denied(PasswordChangeFailure.CREDENTIAL_CHANGED);
            }
            Instant now = clock.instant();
            if (lockouts.isLocked(current.loginFailures(), now)) {
                return denied(PasswordChangeFailure.CURRENT_PASSWORD_INVALID);
            }
            Optional<SessionLifecycleStore.LockedSession> selected = sessions.lockSession(caller.sessionId());
            if (selected.isEmpty() || !eligible(selected.orElseThrow(), caller, now)) {
                return denied(PasswordChangeFailure.SESSION_UNAVAILABLE);
            }
            if (!matches) {
                LoginLockoutPolicy.State next = lockouts.failure(current.loginFailures(), now);
                if (!accounts.compareAndSetLoginFailures(caller.userId(), current.loginFailures(), next)) {
                    throw new IllegalStateException("Serialized IAM password failure state changed unexpectedly");
                }
                // Failure counters intentionally commit on a denied current password.
                return TransactionOutcome.commit(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID));
            }
            accounts.replacePassword(caller.userId(), original.encodedPassword(), replacement);
            sessions.revokeOtherSessions(caller.userId(), caller.sessionId(), now);
            return TransactionOutcome.commit(Result.ok(PasswordChangeStatus.CHANGED));
        });
    }

    private static boolean eligible(SessionLifecycleStore.LockedSession locked,
            VerifiedIamSession caller, Instant now) {
        LoginSession session = locked.session();
        return session.id().equals(caller.sessionId()) && session.userId().equals(caller.userId())
                && !locked.userDisabled() && session.revokedAt() == null
                && !now.isBefore(session.deadlines().createdAt())
                && now.isBefore(session.deadlines().effectiveExpiresAt());
    }

    private static TransactionOutcome<Result<PasswordChangeStatus, PasswordChangeFailure>> denied(PasswordChangeFailure failure) {
        return TransactionOutcome.abstain(Result.err(failure));
    }
}
