package de.hsh.favs.sharewise.groups.internal.events;

import de.hsh.favs.sharewise.groups.internal.application.projection.IamUserProjectionService;
import de.hsh.favs.sharewise.identity.events.UserRegisteredV1;
import de.hsh.favs.sharewise.shared.infra.di.ContributingOperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import java.util.List;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event ingress for IAM user facts: one Group operation per delivery (P2.4a, D6).
 *
 * <p>The listener owns no transaction; the dispatched operation commits through
 * {@code Transactions}, and Spring Modulith completes the publication only after the listener
 * returned. It must stay {@code @Async}: recovery re-invokes it from framework code, where a
 * synchronous call could run inside an unrelated transaction. The listener ID is persisted in the
 * publication registry; renaming it requires draining outstanding publications first.
 *
 * <p>Uses the ingress abstraction with event-side contributors. Events carry no caller evidence,
 * so there are none today; the HTTP ingress dispatcher is request-scoped and cannot be used here.
 */
@Component
class IamUserEventListener {

    static final String LISTENER_ID = "groups.iam-user-projection.v1";

    private final OperationIngressDispatcher ingress;

    IamUserEventListener(OperationDispatcher dispatcher) {
        this.ingress = new ContributingOperationDispatcher(dispatcher, List.of());
    }

    @Async
    @TransactionalEventListener(id = LISTENER_ID)
    public void on(UserRegisteredV1 event) {
        ingress.dispatch(IamUserProjectionService.class, projection -> {
            projection.project(event);
            return null;
        });
    }
}
