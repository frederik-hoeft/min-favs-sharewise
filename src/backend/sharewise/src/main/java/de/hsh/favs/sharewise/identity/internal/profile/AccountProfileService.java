package de.hsh.favs.sharewise.identity.internal.profile;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Operation-scoped account read and profile mutation; never accepts a caller-supplied user ID. */
public final class AccountProfileService {

    private final IdentityContext identity;
    private final Transactions transactions;
    private final AccountProfileStore profiles;

    public AccountProfileService(IdentityContext identity, Transactions transactions, AccountProfileStore profiles) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public Result<AccountProfile, AccountProfileFailure> get() {
        UUID userId = identity.requireUserId();
        return transactions.execute(() -> TransactionOutcome.abstain(result(profiles.findActive(userId))));
    }

    public Result<AccountProfile, AccountProfileFailure> setDisplayName(DisplayNameUpdate command) {
        Objects.requireNonNull(command, "command");
        UUID userId = identity.requireUserId();
        return transactions.execute(() -> {
            Optional<AccountProfile> account = profiles.setDisplayNameIfActive(userId, command.value());
            Result<AccountProfile, AccountProfileFailure> result = result(account);
            return account.isPresent() ? TransactionOutcome.commit(result) : TransactionOutcome.abstain(result);
        });
    }

    private static Result<AccountProfile, AccountProfileFailure> result(Optional<AccountProfile> account) {
        return account.<Result<AccountProfile, AccountProfileFailure>>map(Result::ok)
                .orElseGet(() -> Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE));
    }
}
