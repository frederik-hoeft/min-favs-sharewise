package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.FinancialActorStore;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.Objects;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Applies trusted Group-originated facts to Finance's group roots and retained actors. Each
 * operation owns exactly one transaction segment, takes the group's root lock before reading
 * mutable state, and is idempotent with respect to committed state, so redelivery after any
 * failure or uncertain commit is safe. Changing outcomes commit; no-change outcomes abstain.
 *
 * <p>The service performs no caller authorization: only Finance's own inbound event adapter may
 * invoke it, and the module boundary keeps other callers out. It never retries and neither
 * catches nor maps persistence or lock failures. Root and actor writes share the segment's
 * physical transaction. A failure inside the segment rolls it back, so a confirmed rollback
 * commits nothing of the attempt; a failed commit can leave the outcome uncertain, a failure
 * after commit leaves the changes durable, and a finalization failure can replace the original
 * exception, which is then retained as suppressed. Any exception means the fact must not be
 * acknowledged; idempotent redelivery reprocesses it safely in every case.
 */
@Component
@Scope("operation")
public class FinanceProjectionService {

    private final Transactions transactions;
    private final FinancialGroupStore groups;
    private final FinancialActorStore actors;

    FinanceProjectionService(Transactions transactions, FinancialGroupStore groups, FinancialActorStore actors) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    /**
     * Opens the group's root if it is still provisioning, provisioning an absent root first.
     * A root in any later lifecycle state is left untouched. Never changes actors, the root's
     * revision or its creation time.
     */
    public ActivationOutcome activate(ActivateFinancialGroup command) {
        Objects.requireNonNull(command, "command");
        return transactions.execute(() -> {
            FinancialGroup root = groups.ensureAndLock(command.groupId());
            return switch (root.state()) {
                case PROVISIONING -> {
                    groups.activate(root);
                    yield TransactionOutcome.commit(ActivationOutcome.ACTIVATED);
                }
                case OPEN, CLOSED, ARCHIVED -> TransactionOutcome.abstain(ActivationOutcome.UNCHANGED);
            };
        });
    }

    /**
     * Applies a full-state membership fact in any root lifecycle state, provisioning an absent
     * root. The retained actor changes only if it is unknown or the fact's revision is newer.
     *
     * @throws ConflictingMembershipFactException if the retained actor has the fact's revision but
     *     another state; nothing is written
     */
    public MembershipOutcome applyMembershipChange(ApplyMembershipChange change) {
        Objects.requireNonNull(change, "change");
        return transactions.execute(() -> {
            // The root lock serializes every actor write of the group, so the read below needs no lock.
            groups.ensureAndLock(change.groupId());
            MembershipTransition transition =
                    MembershipTransitionPolicy.decide(actors.find(change.groupId(), change.userId()), change);
            return switch (transition) {
                case MembershipTransition.Insert(FinancialActor actor) -> {
                    actors.insert(actor);
                    yield TransactionOutcome.commit(MembershipOutcome.INSERTED);
                }
                case MembershipTransition.Supersede(FinancialActor current, FinancialActor next) -> {
                    actors.supersede(current, next);
                    yield TransactionOutcome.commit(MembershipOutcome.SUPERSEDED);
                }
                case MembershipTransition.Duplicate _ -> TransactionOutcome.abstain(MembershipOutcome.DUPLICATE);
                case MembershipTransition.Stale _ -> TransactionOutcome.abstain(MembershipOutcome.STALE);
                // Thrown inside the callback so the segment rolls back and the fact stays unacknowledged.
                case MembershipTransition.Contradiction(FinancialActor current, FinancialActor received) ->
                    throw new ConflictingMembershipFactException(current, received);
            };
        });
    }
}
