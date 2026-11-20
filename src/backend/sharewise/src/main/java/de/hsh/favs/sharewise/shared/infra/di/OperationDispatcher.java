package de.hsh.favs.sharewise.shared.infra.di;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Enters a fresh application-operation DI scope and invokes one Spring-managed entry service.
 * Dispatch does not begin a database transaction; application services own transaction segments.
 */
public interface OperationDispatcher {

    <S, R> R dispatch(Class<S> entryType, Function<S, R> action);

    /**
     * Registers typed caller inputs before starting an operation. Initializers may
     * consume them to configure operation-scoped state before entry-service resolution.
     */
    <S, R> R dispatch(
        Consumer<OperationInputRegistry> registerInputs,
        Class<S> entryType,
        Function<S, R> action
    );
}
