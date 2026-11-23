package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** IAM registration orchestration, wired as an operation-scoped bean. */
public final class RegistrationService {

    private final Transactions transactions;
    private final AccountStore accounts;
    private final CredentialEncoder credentials;
    private final InitialAuthentication authentication;
    private final UserEventPublisher events;
    private final Clock clock;

    public RegistrationService(Transactions transactions, AccountStore accounts,
            CredentialEncoder credentials, InitialAuthentication authentication, UserEventPublisher events,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result<AuthenticationResult, RegistrationFailure> register(RegistrationCommand command) {
        Objects.requireNonNull(command, "command");
        // Adaptive password hashing must not hold a database transaction open.
        String encoded = credentials.encode(command.password());
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Credential encoder returned no verifier");
        }
        return transactions.execute(() -> {
            Optional<AccountSnapshot> inserted = accounts.insert(command.email(), encoded);
            if (inserted.isEmpty()) {
                return TransactionOutcome.abstain(Result.err(RegistrationFailure.EMAIL_UNAVAILABLE));
            }
            Instant now = clock.instant();
            UUID userId = inserted.orElseThrow().userId();
            AuthenticationResult established = authentication.create(userId, now);
            // Same segment as the account: the delivery obligation commits or rolls back with it.
            events.userRegistered(userId, now);
            return TransactionOutcome.commit(Result.ok(established));
        });
    }
}
