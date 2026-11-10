package de.hsh.favs.sharewise.testsupport.operation;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import java.util.Objects;
import java.util.function.Function;

/**
 * Test-only entry point for invoking and inspecting beans inside a real operation DI scope.
 * Scope-only execution does not start a database transaction.
 */
public final class OperationTestHarness {

    private final ServiceScopeFactory scopeFactory;

    public OperationTestHarness(ServiceScopeFactory scopeFactory) {
        this.scopeFactory = Objects.requireNonNull(scopeFactory);
    }

    /** Runs a callback in a new, independent scope and closes it on every exit path. */
    public <T> T inOperationScope(Function<OperationTestScope, T> test) {
        Objects.requireNonNull(test);
        try (ServiceScope scope = scopeFactory.createScope()) {
            return scope.run(provider -> test.apply(new OperationTestScope(provider)));
        }
    }
}
