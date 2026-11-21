package de.hsh.favs.sharewise.shared.infra.di;

import java.util.Optional;

/**
 * Maps one optional caller-supplied input into an operation-scoped configuration.
 * Initializers are Spring-managed and run before the operation entry service is resolved.
 * Each initializer must be independent of other initializers at the same priority.
 */
public interface ScopedServiceInitializer<C extends OperationScopedConfiguration, D> {

    Class<C> configurationType();

    Class<D> inputType();

    /** Lower priorities run first, including negative priorities. */
    default int priority() {
        return 0;
    }

    /** Decides whether a missing input is acceptable; a configuration is always required. */
    void initialize(C configuration, Optional<D> input);
}
