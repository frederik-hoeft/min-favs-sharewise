package de.hsh.favs.sharewise.shared.infra.di;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Caller-context DI adapter over the regular operation dispatcher.
 *
 * <p>Construct it in the owning outer scope with that scope's contributors.
 * Their contributions are evaluated before the inner operation scope exists;
 * failures prevent dispatch, and duplicate input types are rejected by the
 * normal registry. Contributors do not become operation-scoped beans.
 */
public final class ContributingOperationDispatcher implements OperationIngressDispatcher {

    private final OperationDispatcher dispatcher;
    private final List<OperationInputContributor> contributors;

    /**
     * Captures the contributors supplied by the caller-side DI environment.
     *
     * @param dispatcher core operation dispatcher
     * @param contributors caller-owned contributors in execution order
     */
    public ContributingOperationDispatcher(OperationDispatcher dispatcher, List<OperationInputContributor> contributors) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
    }

    @Override
    public <S, R> R dispatch(Class<S> entryType, Function<S, R> action) {
        return dispatcher.dispatch(inputs -> {
            for (OperationInputContributor contributor : contributors) {
                contributor.contribute(inputs);
            }
        }, entryType, action);
    }
}
