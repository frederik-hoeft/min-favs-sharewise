package de.hsh.favs.sharewise.groups.internal.application.projection;

import de.hsh.favs.sharewise.groups.internal.application.access.GroupUserRegistry;
import de.hsh.favs.sharewise.identity.events.UserRegisteredV1;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.Objects;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Applies IAM registration facts to Group's local user projection ({@code groups.users}) in a
 * Group-owned transaction (P2.4a/P2.2d).
 *
 * <p>System operation: it is triggered by a durable event, not by a caller, so it performs no
 * caller admission. Idempotent by construction: provisioning a known ID has no effect, so
 * duplicate, concurrent and redelivered events are harmless and no inbox is needed.
 */
@Component
@Scope("operation")
public class IamUserProjectionService {

    private final GroupUserRegistry users;
    private final Transactions transactions;

    public IamUserProjectionService(GroupUserRegistry users, Transactions transactions) {
        this.users = Objects.requireNonNull(users, "users");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public void project(UserRegisteredV1 event) {
        Objects.requireNonNull(event, "event");
        transactions.execute(() -> {
            users.provision(event.userId());
            return TransactionOutcome.commit(null);
        });
    }
}
