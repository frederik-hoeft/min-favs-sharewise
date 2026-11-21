package de.hsh.favs.sharewise.shared.infra.di.internal;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import de.hsh.favs.sharewise.shared.infra.di.ServiceProvider;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import java.lang.ScopedValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** Resolves an operation entry service with Spring, in a uniquely owned DI scope. */
final class SpringOperationDispatcher implements OperationDispatcher {

    private final SpringServiceScopeFactory scopeFactory;
    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<ScopedServiceInitializer<?, ?>> initializers;
    // Remains bound through destruction, after the DI scope binding has unwound.
    private final ScopedValue<Boolean> dispatchInProgress = ScopedValue.newInstance();

    SpringOperationDispatcher(
        SpringServiceScopeFactory scopeFactory,
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<ScopedServiceInitializer<?, ?>> initializers
    ) {
        this.scopeFactory = Objects.requireNonNull(scopeFactory);
        this.beanFactory = Objects.requireNonNull(beanFactory);
        this.initializers = Objects.requireNonNull(initializers);
    }

    @Override
    public <S, R> R dispatch(Class<S> entryType, Function<S, R> action) {
        return dispatch(inputs -> { }, entryType, action);
    }

    @Override
    public <S, R> R dispatch(
        Consumer<OperationInputRegistry> registerInputs,
        Class<S> entryType,
        Function<S, R> action
    ) {
        Objects.requireNonNull(registerInputs);
        Objects.requireNonNull(entryType);
        Objects.requireNonNull(action);
        if (dispatchInProgress.isBound() || scopeFactory.hasActiveBinding()) {
            throw new IllegalStateException("Cannot dispatch an operation within an active operation scope");
        }

        return ScopedValue.where(dispatchInProgress, Boolean.TRUE).call(() -> {
            DefaultOperationInputRegistry inputs = new DefaultOperationInputRegistry();
            try {
                try {
                    registerInputs.accept(inputs);
                } finally {
                    // Retained callback references cannot mutate a registry after setup.
                    inputs.closeRegistration();
                }

                try (ServiceScope scope = scopeFactory.createScope()) {
                    return scope.run(provider -> {
                        initializeConfigurations(provider, inputs);
                        return action.apply(provider.getRequiredService(entryType));
                    });
                }
            } finally {
                // A retained registration callback must not prolong the lifetime of ingress data.
                inputs.release();
            }
        });
    }

    private void initializeConfigurations(ServiceProvider provider, DefaultOperationInputRegistry inputs) {
        // Resolve Spring initializer beans inside the lexical operation binding.
        List<ScopedServiceInitializer<?, ?>> ordered = initializers.stream()
            .sorted(Comparator.comparingInt(ScopedServiceInitializer::priority))
            .toList();
        IdentityHashMap<OperationScopedConfiguration, Boolean> targets = new IdentityHashMap<>();
        List<OperationScopedConfiguration> configurations = new ArrayList<>();
        for (ScopedServiceInitializer<?, ?> initializer : ordered) {
            OperationScopedConfiguration configuration = initialize(initializer, provider, inputs);
            if (targets.put(configuration, Boolean.TRUE) == null) {
                configurations.add(configuration);
            }
        }
        for (OperationScopedConfiguration configuration : configurations) {
            configuration.finishInitialization();
        }
    }

    private <C extends OperationScopedConfiguration, D> C initialize(
        ScopedServiceInitializer<C, D> initializer,
        ServiceProvider provider,
        DefaultOperationInputRegistry inputs
    ) {
        Class<C> configurationType = Objects.requireNonNull(initializer.configurationType(), "configurationType");
        Class<D> inputType = Objects.requireNonNull(initializer.inputType(), "inputType");
        requireOperationScoped(configurationType);
        C configuration = provider.getRequiredService(configurationType);
        initializer.initialize(configuration, inputs.find(inputType));
        return configuration;
    }

    private void requireOperationScoped(Class<? extends OperationScopedConfiguration> type) {
        String[] names = beanFactory.getBeanNamesForType(type, true, false);
        if (names.length != 1) {
            throw new IllegalStateException("Expected exactly one Spring configuration bean of type " + type.getName());
        }
        BeanDefinition definition = beanFactory.getMergedBeanDefinition(names[0]);
        if (!SpringOperationScope.NAME.equals(definition.getScope())) {
            throw new IllegalStateException("Initializer target must have operation scope: " + type.getName());
        }
    }
}
