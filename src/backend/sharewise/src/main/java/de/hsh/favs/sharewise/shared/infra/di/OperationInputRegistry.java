package de.hsh.favs.sharewise.shared.infra.di;

/** Registers caller-owned inputs for a single operation before its DI scope is activated. */
public interface OperationInputRegistry {

    /**
     * Registers an instance under an exact declared type. An input is not a Spring bean
     * and is not owned or destroyed by the operation scope.
     *
     * @throws NullPointerException if the key or instance is null
     * @throws IllegalArgumentException if the instance does not match its key or the key is duplicated
     * @throws IllegalStateException if the registration phase has ended or the caller is not the owner thread
     */
    <T> void add(Class<T> type, T instance);
}
