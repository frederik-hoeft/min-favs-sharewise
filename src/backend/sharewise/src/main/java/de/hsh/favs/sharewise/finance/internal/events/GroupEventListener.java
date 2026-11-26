package de.hsh.favs.sharewise.finance.internal.events;

import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.projection.ActivateFinancialGroup;
import de.hsh.favs.sharewise.finance.internal.projection.ActivationOutcome;
import de.hsh.favs.sharewise.finance.internal.projection.ApplyMembershipChange;
import de.hsh.favs.sharewise.finance.internal.projection.FinanceProjectionService;
import de.hsh.favs.sharewise.finance.internal.projection.MembershipOutcome;
import de.hsh.favs.sharewise.groups.events.GroupCreatedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberAddedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberRemovedV1;
import de.hsh.favs.sharewise.shared.infra.di.ContributingOperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event ingress for Group creation and membership facts: one Finance projection operation per
 * delivery (P3.2b). This is the only place where Group event types meet Finance commands.
 *
 * <p>The listener owns no transaction; the dispatched operation commits or abstains through
 * {@code Transactions}. Every returned outcome, committing or abstaining, means committed Finance
 * state reflects the fact, so the handler returns normally and Spring Modulith completes the
 * publication. Every exception, including {@code ConflictingMembershipFactException}, propagates
 * unchanged and leaves the publication incomplete for resubmission; the listener never catches,
 * maps or retries. Handlers must stay {@code @Async}: recovery re-invokes them from framework
 * code, where a synchronous call could run inside an unrelated transaction. Events of one group
 * may be delivered concurrently and in any order; the projection's root lock and per-actor
 * revision guard make that safe.
 *
 * <p>Each event type has its own listener ID, persisted in the publication registry with every
 * outstanding publication. Renaming an ID or moving an event type requires draining outstanding
 * publications first.
 *
 * <p>Uses the ingress abstraction with event-side contributors. Events carry no caller evidence,
 * so there are none today; the HTTP ingress dispatcher is request-scoped and cannot be used here.
 */
@Component
class GroupEventListener {

    static final String ACTIVATION_LISTENER_ID = "finance.group-activation.v1";
    static final String MEMBERSHIP_ADDED_LISTENER_ID = "finance.membership-added.v1";
    static final String MEMBERSHIP_REMOVED_LISTENER_ID = "finance.membership-removed.v1";

    private static final Logger LOG = LoggerFactory.getLogger(GroupEventListener.class);

    private final OperationIngressDispatcher ingress;

    @Autowired
    GroupEventListener(OperationDispatcher dispatcher) {
        this(new ContributingOperationDispatcher(dispatcher, List.of()));
    }

    GroupEventListener(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @Async
    @TransactionalEventListener(id = ACTIVATION_LISTENER_ID)
    public void on(GroupCreatedV1 event) {
        ActivationOutcome outcome = ingress.dispatch(FinanceProjectionService.class,
            projection -> projection.activate(new ActivateFinancialGroup(event.groupId())));
        handled(event.eventId(), GroupCreatedV1.class, event.groupId(), outcome);
    }

    @Async
    @TransactionalEventListener(id = MEMBERSHIP_ADDED_LISTENER_ID)
    public void on(GroupMemberAddedV1 event) {
        MembershipOutcome outcome = apply(event.groupId(), event.userId(), event.membershipRevision(),
            MembershipState.ACTIVE);
        handled(event.eventId(), GroupMemberAddedV1.class, event.groupId(), outcome);
    }

    @Async
    @TransactionalEventListener(id = MEMBERSHIP_REMOVED_LISTENER_ID)
    public void on(GroupMemberRemovedV1 event) {
        MembershipOutcome outcome = apply(event.groupId(), event.userId(), event.membershipRevision(),
            MembershipState.REMOVED);
        handled(event.eventId(), GroupMemberRemovedV1.class, event.groupId(), outcome);
    }

    private MembershipOutcome apply(UUID groupId, UUID userId, long revision, MembershipState state) {
        return ingress.dispatch(FinanceProjectionService.class, projection ->
            projection.applyMembershipChange(new ApplyMembershipChange(groupId, userId, revision, state)));
    }

    private static void handled(UUID eventId, Class<?> type, UUID groupId, Enum<?> outcome) {
        LOG.debug("Applied {} {} for group {}: {}", type.getSimpleName(), eventId, groupId, outcome);
    }
}
