package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import java.lang.ScopedValue;
import java.util.Objects;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** Per-application-context scope factory and lexical binding owner. */
final class SpringServiceScopeFactory implements ServiceScopeFactory, OperationScopeAccess, BeanFactoryPostProcessor {

    private final ScopedValue<SpringServiceScope> current = ScopedValue.newInstance();
    private ConfigurableListableBeanFactory beanFactory;

    // Register during bean-factory post-processing so scoped bean definitions
    // never require eager construction of a separate configuration singleton.
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory);
        beanFactory.registerScope(SpringOperationScope.NAME, new SpringOperationScope(this));
    }

    @Override
    public ServiceScope createScope() {
        if (beanFactory == null) {
            throw new IllegalStateException("Operation scope has not been registered with Spring");
        }
        return new SpringServiceScope(this, beanFactory);
    }

    <T> T activate(SpringServiceScope scope, java.util.function.Supplier<T> action) {
        return ScopedValue.where(current, scope).call(action::get);
    }

    @Override
    public ScopeLease captureCurrent() {
        SpringServiceScope scope = currentScope();
        return scope::requireCurrent;
    }

    boolean hasActiveBinding() {
        return current.isBound();
    }

    SpringServiceScope currentScope() {
        if (!current.isBound()) {
            throw new IllegalStateException("No operation scope is active in this Spring context");
        }
        SpringServiceScope scope = current.get();
        scope.ensureActiveOnOwnerThread();
        return scope;
    }

    void ensureCurrent(SpringServiceScope expected) {
        if (currentScope() != expected) {
            throw new IllegalStateException("Service provider belongs to a different operation scope");
        }
    }
}
