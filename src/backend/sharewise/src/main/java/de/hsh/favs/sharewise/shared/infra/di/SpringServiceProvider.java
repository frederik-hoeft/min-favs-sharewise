package de.hsh.favs.sharewise.shared.infra.di;

import de.hsh.favs.sharewise.shared.ServiceProvider;
import java.util.Objects;
import org.springframework.beans.factory.BeanFactory;

/** A scope-bound view of Spring's existing bean resolution pipeline. */
final class SpringServiceProvider implements ServiceProvider {

    private final SpringServiceScope scope;
    private final BeanFactory beanFactory;

    SpringServiceProvider(SpringServiceScope scope, BeanFactory beanFactory) {
        this.scope = scope;
        this.beanFactory = beanFactory;
    }

    @Override
    public <T> T getRequiredService(Class<T> serviceType) {
        scope.requireCurrent();
        return beanFactory.getBean(Objects.requireNonNull(serviceType));
    }
}
