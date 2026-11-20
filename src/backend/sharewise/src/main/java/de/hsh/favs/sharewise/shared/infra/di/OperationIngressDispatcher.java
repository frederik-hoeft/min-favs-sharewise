package de.hsh.favs.sharewise.shared.infra.di;

import java.util.function.Function;

/**
 * Dispatches through the caller context's registered input contributors.
 * Unlike the underlying operation dispatcher, callers need not know which
 * application modules initialize state in the new operation scope.
 */
public interface OperationIngressDispatcher {

    /**
     * Runs the contributors, then initializes and executes a fresh operation scope.
     *
     * @param entryType operation entry service type
     * @param action operation callback
     * @param <S> entry service type
     * @param <R> result type
     * @return result of the operation
     */
    <S, R> R dispatch(Class<S> entryType, Function<S, R> action);
}
