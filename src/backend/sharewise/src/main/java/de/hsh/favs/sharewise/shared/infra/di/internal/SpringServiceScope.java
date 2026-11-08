package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.ServiceProvider;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** A single-use scope owning only its operation-scoped beans, never Spring singletons. */
final class SpringServiceScope implements ServiceScope {

    private enum State { NEW, ACTIVE, FINISHED, CLOSED }

    private final SpringServiceScopeFactory factory;
    private final Thread owner = Thread.currentThread();
    private final String id = UUID.randomUUID().toString();
    private final ServiceProvider provider;
    private final Map<String, Object> beans = new LinkedHashMap<>();
    private final Map<String, Runnable> destructionCallbacks = new LinkedHashMap<>();
    private State state = State.NEW;

    SpringServiceScope(SpringServiceScopeFactory factory, ConfigurableListableBeanFactory beanFactory) {
        this.factory = factory;
        this.provider = new SpringServiceProvider(this, beanFactory);
    }

    @Override
    public <T> T run(Function<ServiceProvider, T> action) {
        Objects.requireNonNull(action);
        ensureOwnerThread();
        if (state != State.NEW) {
            throw new IllegalStateException("An operation scope can only be activated once");
        }

        state = State.ACTIVE;
        try {
            return factory.activate(this, () -> action.apply(provider));
        } finally {
            state = State.FINISHED;
        }
    }

    @Override
    public void close() {
        ensureOwnerThread();
        if (state == State.CLOSED) {
            return;
        }
        if (state == State.ACTIVE) {
            throw new IllegalStateException("An active operation scope cannot be closed");
        }

        state = State.CLOSED;
        List<Runnable> callbacks = new ArrayList<>(destructionCallbacks.values());
        Collections.reverse(callbacks);
        destructionCallbacks.clear();
        beans.clear();

        Throwable failure = null;
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable thrown) {
                if (failure == null) {
                    failure = thrown;
                } else if (failure != thrown) {
                    failure.addSuppressed(thrown);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Operation scope cleanup failed", failure);
        }
    }

    String id() {
        return id;
    }

    void requireCurrent() {
        ensureActiveOnOwnerThread();
        factory.ensureCurrent(this);
    }

    void ensureActiveOnOwnerThread() {
        ensureOwnerThread();
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Operation scope is not active");
        }
    }

    Object getOrCreate(String name, ObjectFactory<?> objectFactory) {
        if (beans.containsKey(name)) {
            return beans.get(name);
        }
        try {
            Object bean = objectFactory.getObject();
            beans.put(name, bean);
            return bean;
        } catch (RuntimeException | Error failure) {
            destructionCallbacks.remove(name);
            throw failure;
        }
    }

    Object remove(String name) {
        destructionCallbacks.remove(name);
        return beans.remove(name);
    }

    void registerDestructionCallback(String name, Runnable callback) {
        destructionCallbacks.put(name, Objects.requireNonNull(callback));
    }

    private void ensureOwnerThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Operation scope belongs to another thread");
        }
    }
}
