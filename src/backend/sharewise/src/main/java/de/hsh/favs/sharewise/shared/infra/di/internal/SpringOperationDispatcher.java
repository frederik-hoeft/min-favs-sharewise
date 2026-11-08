package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import java.lang.ScopedValue;
import java.util.Objects;
import java.util.function.Function;

/** Resolves an operation entry service with Spring, in a uniquely owned DI scope. */
final class SpringOperationDispatcher implements OperationDispatcher {

    private final SpringServiceScopeFactory scopeFactory;
    // Remains bound through destruction, after the DI scope binding has unwound.
    private final ScopedValue<Boolean> dispatchInProgress = ScopedValue.newInstance();

    SpringOperationDispatcher(SpringServiceScopeFactory scopeFactory) {
        this.scopeFactory = Objects.requireNonNull(scopeFactory);
    }

    @Override
    public <S, R> R dispatch(Class<S> entryType, Function<S, R> action) {
        Objects.requireNonNull(entryType);
        Objects.requireNonNull(action);
        if (dispatchInProgress.isBound() || scopeFactory.hasActiveBinding()) {
            throw new IllegalStateException("Cannot dispatch an operation within an active operation scope");
        }

        return ScopedValue.where(dispatchInProgress, Boolean.TRUE).call(() -> {
            try (ServiceScope scope = scopeFactory.createScope()) {
                return scope.run(provider -> action.apply(provider.getRequiredService(entryType)));
            }
        });
    }
}
