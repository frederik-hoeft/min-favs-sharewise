package de.hsh.favs.sharewise.finance.internal.events;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.testsupport.execution.Eventually;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.messaging.EventPublications;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Observes Group → Finance delivery in E2E tests: committed Finance state and the outstanding
 * publications of the Finance Group listeners, each through bounded polling on independent
 * connections. Producer-side tests use it to wait for asynchronous Finance writes instead of
 * sleeping or disabling delivery.
 */
public final class GroupDeliveryFixture {

    public static final String ACTIVATION = GroupEventListener.ACTIVATION_LISTENER_ID;
    public static final String MEMBERSHIP_ADDED = GroupEventListener.MEMBERSHIP_ADDED_LISTENER_ID;
    public static final String MEMBERSHIP_REMOVED = GroupEventListener.MEMBERSHIP_REMOVED_LISTENER_ID;
    public static final List<String> LISTENERS = List.of(ACTIVATION, MEMBERSHIP_ADDED, MEMBERSHIP_REMOVED);

    private GroupDeliveryFixture() {
    }

    /** Waits until every Group publication to Finance has completed and been deleted. */
    public static void awaitDelivered(IntegrationTestContext fixture) {
        EventPublications.awaitCompleted(fixture, LISTENERS);
    }

    /** Waits until the group's Finance root is open with financial revision zero. */
    public static void awaitOpenRoot(IntegrationTestContext fixture, UUID groupId) {
        FinancialGroup expected = new FinancialGroup(groupId, FinancialGroupState.OPEN, 0);
        Eventually.await("open Finance root " + groupId,
            () -> FinanceTestFixture.root(fixture, groupId).equals(Optional.of(expected)));
    }

    /** Waits until the retained actor has the given state and upstream revision. */
    public static void awaitActor(IntegrationTestContext fixture, UUID groupId, UUID userId, MembershipState state,
            long revision) {
        FinancialActor expected = new FinancialActor(groupId, userId, state, revision);
        Eventually.await("Finance actor " + expected,
            () -> FinanceTestFixture.actors(fixture, groupId).contains(expected));
    }
}
