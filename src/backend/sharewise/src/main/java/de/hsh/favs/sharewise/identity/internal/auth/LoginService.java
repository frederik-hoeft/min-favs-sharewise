package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Password login with expensive verification outside the serialized account mutation segment. */
public final class LoginService {

    private final Transactions transactions;
    private final AccountStore accounts;
    private final CredentialEncoder credentials;
    private final InitialAuthentication authentication;
    private final LoginLockoutPolicy lockouts;
    private final Clock clock;
    private final String dummyEncodedPassword;

    public LoginService(Transactions transactions, AccountStore accounts, CredentialEncoder credentials,
            InitialAuthentication authentication, LoginLockoutPolicy lockouts, Clock clock,
            String dummyEncodedPassword) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.lockouts = Objects.requireNonNull(lockouts, "lockouts");
        this.clock = Objects.requireNonNull(clock, "clock");
        // The injected dummy verifier must match the production algorithm and cost,
        // not a plaintext secret or a cheap special case.
        this.dummyEncodedPassword = Objects.requireNonNull(dummyEncodedPassword, "dummyEncodedPassword");
        if (dummyEncodedPassword.isBlank()) {
            throw new IllegalArgumentException("Dummy credential verifier must not be empty");
        }
    }

    public Result<AuthenticationResult, LoginFailure> login(LoginCommand command) {
        Objects.requireNonNull(command, "command");
        Optional<AccountSnapshot> snapshot = transactions.execute(() ->
                TransactionOutcome.abstain(accounts.findByEmail(command.email())));

        // Unknown accounts get the same expensive verifier path, without fabricating a user.
        String verifier = snapshot.map(AccountSnapshot::encodedPassword).orElse(dummyEncodedPassword);
        boolean matched = credentials.matches(command.password(), verifier);
        if (snapshot.isEmpty()) {
            return Result.err(LoginFailure.INVALID_CREDENTIALS);
        }
        AccountSnapshot initial = snapshot.orElseThrow();
        return transactions.execute(() -> {
            Optional<AccountSnapshot> current = accounts.findByEmailForUpdate(command.email());
            if (current.isEmpty()) {
                return denied();
            }
            AccountSnapshot locked = current.orElseThrow();
            // A credential update or delete/recreate after verification invalidates the result.
            if (!locked.userId().equals(initial.userId())
                    || !locked.encodedPassword().equals(initial.encodedPassword())
                    || locked.isDisabled()) {
                return denied();
            }
            Instant now = clock.instant();
            LoginLockoutPolicy.State previous = locked.loginFailures();
            if (lockouts.isLocked(previous, now)) {
                return denied();
            }
            LoginLockoutPolicy.State next = matched ? lockouts.success() : lockouts.failure(previous, now);
            if (!next.equals(previous) && !accounts.compareAndSetLoginFailures(
                    locked.userId(), previous, next)) {
                throw new IllegalStateException("Serialized IAM login state changed unexpectedly");
            }
            if (!matched) {
                // Commit the failure counter/lock despite returning an ordinary denial.
                return TransactionOutcome.commit(Result.err(LoginFailure.INVALID_CREDENTIALS));
            }
            AuthenticationResult established = authentication.create(locked.userId(), now);
            return TransactionOutcome.commit(Result.ok(established));
        });
    }

    private static TransactionOutcome<Result<AuthenticationResult, LoginFailure>> denied() {
        return TransactionOutcome.abstain(Result.err(LoginFailure.INVALID_CREDENTIALS));
    }
}
