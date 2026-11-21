package de.hsh.favs.sharewise.shared.infra.di;

/**
 * Base for operation-scoped configuration initialized before entry-service construction.
 * Implementations guard every exposed state read with {@link #requireReady()} and
 * every state mutation with {@link #requireInitializing()}.
 *
 * <p>The dispatcher finishes each initialized configuration once, after all initializers
 * have run. These objects are intended for narrowly scoped ingress data, not ordinary
 * application method arguments or mutable application services.
 */
public abstract class OperationScopedConfiguration {

    private boolean ready;

    /** Performs final validation before making the configuration readable. */
    protected void validate() { }

    /** Rejects reads before successful initialization and final validation. */
    protected final void requireReady() {
        if (!ready) {
            throw new IllegalStateException("Operation configuration has not been finalized");
        }
    }

    /** Rejects changes after successful initialization and final validation. */
    protected final void requireInitializing() {
        if (ready) {
            throw new IllegalStateException("Operation configuration is already finalized");
        }
    }

    /** Completes the initialization lifecycle; called once by the dispatcher. */
    public final void finishInitialization() {
        requireInitializing();
        validate();
        ready = true;
    }
}
