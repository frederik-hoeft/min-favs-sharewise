package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.util.UUID;

/**
 * Invokes the projection operations as the P3.2b listener will: one production dispatch, and
 * therefore one fresh operation scope and segment owner, per fact.
 */
final class ProjectionDispatch {

    private ProjectionDispatch() {
    }

    static ActivationOutcome activate(IntegrationTestContext fixture, UUID group) {
        return dispatcher(fixture).dispatch(FinanceProjectionService.class,
                service -> service.activate(new ActivateFinancialGroup(group)));
    }

    static MembershipOutcome apply(IntegrationTestContext fixture, UUID group, UUID user, long revision,
            MembershipState state) {
        return dispatcher(fixture).dispatch(FinanceProjectionService.class,
                service -> service.applyMembershipChange(new ApplyMembershipChange(group, user, revision, state)));
    }

    private static OperationDispatcher dispatcher(IntegrationTestContext fixture) {
        return fixture.context().getBean(OperationDispatcher.class);
    }
}
