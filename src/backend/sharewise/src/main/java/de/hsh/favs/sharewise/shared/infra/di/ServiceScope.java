package de.hsh.favs.sharewise.shared.infra.di;

import java.util.function.Function;

/** Owns one set of operation-scoped beans and their destruction callbacks. */
public interface ServiceScope extends AutoCloseable {

    /**
     * Activates this scope for one synchronous callback. The scope remains open
     * after the callback and must be closed by its owner.
     */
    <T> T run(Function<ServiceProvider, T> action);

    /** Permanently closes this scope and destroys its remaining scoped beans. */
    @Override
    void close();
}
