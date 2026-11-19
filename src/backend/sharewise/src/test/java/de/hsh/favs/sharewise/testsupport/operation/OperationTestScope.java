package de.hsh.favs.sharewise.testsupport.operation;

import de.hsh.favs.sharewise.shared.infra.di.ServiceProvider;
import java.util.Objects;

/** A test-facing resolver, valid only during its owning harness callback. */
public final class OperationTestScope {

    private final ServiceProvider provider;

    OperationTestScope(ServiceProvider provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    /** Resolves through the owning Spring context and its currently active operation scope. */
    public <S> S resolve(Class<S> serviceType) {
        return provider.getRequiredService(serviceType);
    }
}
