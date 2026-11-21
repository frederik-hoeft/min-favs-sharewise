package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Local dispatch state; never registered as a Spring bean or published to application services. */
final class DefaultOperationInputRegistry implements OperationInputRegistry {

    private final Thread owner = Thread.currentThread();
    private final Map<Class<?>, Object> inputs = new HashMap<>();
    private boolean closed;

    @Override
    public <T> OperationInputRegistry add(Class<T> type, T instance) {
        ensureOwner();
        if (closed) {
            throw new IllegalStateException("Operation input registration is closed");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
        if (!type.isInstance(instance)) {
            throw new IllegalArgumentException("Operation input does not match its registered type: " + type.getName());
        }
        if (inputs.containsKey(type)) {
            throw new IllegalArgumentException("Duplicate operation input: " + type.getName());
        }
        inputs.put(type, instance);
        return this;
    }

    void closeRegistration() {
        ensureOwner();
        closed = true;
    }

    void release() {
        ensureOwner();
        inputs.clear();
    }

    <T> Optional<T> find(Class<T> type) {
        ensureOwner();
        if (!closed) {
            throw new IllegalStateException("Operation input registration is still open");
        }
        return Optional.ofNullable(type.cast(inputs.get(Objects.requireNonNull(type))));
    }

    private void ensureOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Operation inputs belong to the dispatching thread");
        }
    }
}
