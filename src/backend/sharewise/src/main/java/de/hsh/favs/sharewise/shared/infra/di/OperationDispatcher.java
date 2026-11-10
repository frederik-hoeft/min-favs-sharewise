package de.hsh.favs.sharewise.shared.infra.di;

import java.util.function.Function;

/**
 * Enters a fresh application-operation DI scope and invokes one Spring-managed entry service.
 * Dispatch does not begin a database transaction; application services own transaction segments.
 */
public interface OperationDispatcher {

    <S, R> R dispatch(Class<S> entryType, Function<S, R> action);
}
