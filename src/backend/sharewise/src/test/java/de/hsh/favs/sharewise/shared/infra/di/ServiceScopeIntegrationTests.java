package de.hsh.favs.sharewise.shared.infra.di;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.ServiceProvider;
import de.hsh.favs.sharewise.shared.ServiceScopeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.Scope;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

class ServiceScopeIntegrationTests {

    @Test
    void springConstructsOneScopedGraphAndSharesContextSingletons() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var singleton = context.getBean(SingletonToken.class);

            ScopedState firstScopeState;
            try (var scope = factory.createScope()) {
                firstScopeState = scope.run(provider -> {
                    var first = provider.getRequiredService(FirstService.class);
                    var second = provider.getRequiredService(SecondService.class);
                    assertSame(first.state(), second.state());
                    assertSame(first.state(), provider.getRequiredService(ScopedState.class));
                    assertSame(first.state(), provider.getRequiredService(ConstructorConsumerA.class).state);
                    assertSame(first.state(), provider.getRequiredService(ConstructorConsumerB.class).state);
                    assertSame(singleton, first.singleton());
                    assertSame(singleton, second.singleton());
                    assertEquals("chosen", provider.getRequiredService(QualifiedService.class).selection());
                    assertTrue(provider.getRequiredService(AnnotatedResource.class).initialized);
                    return first.state();
                });
            }

            try (var scope = factory.createScope()) {
                var nextState = scope.run(provider -> provider.getRequiredService(ScopedState.class));
                assertNotSame(firstScopeState, nextState);
                assertSame(singleton, context.getBean(SingletonToken.class));
            }
        }
    }

    @Test
    void noActiveScopeAndEscapedProvidersAreRejected() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            assertThrows(BeanCreationException.class, () -> context.getBean(ScopedState.class));

            var escaped = new AtomicReference<ServiceProvider>();
            try (var scope = factory.createScope()) {
                scope.run(provider -> {
                    escaped.set(provider);
                    assertNotNull(provider.getRequiredService(ScopedState.class));
                    return null;
                });
                assertThrows(IllegalStateException.class,
                    () -> escaped.get().getRequiredService(ScopedState.class));
            }
            assertThrows(IllegalStateException.class,
                () -> escaped.get().getRequiredService(SingletonToken.class));
        }
    }

    @Test
    void nestedIndependentScopesRestoreParentBindingEvenOnException() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            try (var parent = factory.createScope()) {
                parent.run(parentProvider -> {
                    var parentBean = parentProvider.getRequiredService(ScopedState.class);
                    try (var child = factory.createScope()) {
                        assertThrows(IllegalArgumentException.class, () -> child.run(childProvider -> {
                            assertNotSame(parentBean,
                                childProvider.getRequiredService(ScopedState.class));
                            assertThrows(IllegalStateException.class,
                                () -> parentProvider.getRequiredService(ScopedState.class));
                            throw new IllegalArgumentException("child failed");
                        }));
                    }
                    assertSame(parentBean, parentProvider.getRequiredService(ScopedState.class));
                    return null;
                });
            }
            assertThrows(BeanCreationException.class, () -> context.getBean(ScopedState.class));
        }
    }

    @Test
    void independentSpringContextsHaveIndependentScopeBindings() {
        try (var first = newContext(); var second = newContext()) {
            var firstFactory = first.getBean(ServiceScopeFactory.class);
            var secondFactory = second.getBean(ServiceScopeFactory.class);
            assertNotSame(firstFactory, secondFactory);

            try (var firstScope = firstFactory.createScope()) {
                firstScope.run(firstProvider -> {
                    var firstBean = firstProvider.getRequiredService(ScopedState.class);
                    assertThrows(BeanCreationException.class, () -> second.getBean(ScopedState.class));
                    try (var secondScope = secondFactory.createScope()) {
                        secondScope.run(secondProvider -> {
                            assertNotSame(firstBean, secondProvider.getRequiredService(ScopedState.class));
                            return null;
                        });
                    }
                    assertSame(firstBean, firstProvider.getRequiredService(ScopedState.class));
                    return null;
                });
            }
        }
    }

    @Test
    void scopeCanOnlyBeActivatedOnceAndClosingIsTerminal() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var scope = factory.createScope();
            scope.run(provider -> {
                assertThrows(IllegalStateException.class, () -> scope.run(ignored -> null));
                assertThrows(IllegalStateException.class, scope::close);
                return null;
            });
            assertThrows(IllegalStateException.class, () -> scope.run(ignored -> null));
            scope.close();
            scope.close();
            assertThrows(IllegalStateException.class, () -> scope.run(ignored -> null));

            var unused = factory.createScope();
            unused.close();
            assertThrows(IllegalStateException.class, () -> unused.run(ignored -> null));
        }
    }

    @Test
    void removingABeanDiscardsItsDestructionCallbackAndAllowsRecreation() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var count = context.getBean(DestructionCount.class);
            try (var scope = factory.createScope()) {
                scope.run(provider -> {
                    var original = provider.getRequiredService(TrackedResource.class);
                    Scope spi = context.getBeanFactory().getRegisteredScope("operation");
                    assertSame(original, spi.remove("trackedResource"));
                    assertNotSame(original, provider.getRequiredService(TrackedResource.class));
                    assertEquals(0, count.value.get());
                    return null;
                });
            }
            assertEquals(1, count.value.get());
        }
    }

    @Test
    void destructionCallbacksRunInReverseRegistrationOrder() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var calls = new ArrayList<String>();
            try (var scope = factory.createScope()) {
                scope.run(provider -> {
                    Scope spi = context.getBeanFactory().getRegisteredScope("operation");
                    spi.registerDestructionCallback("first", () -> calls.add("first"));
                    spi.registerDestructionCallback("second", () -> calls.add("second"));
                    return null;
                });
            }
            assertEquals(List.of("second", "first"), calls);
        }
    }

    @Test
    void springInitializationAndDestructionAreAppliedToScopedBeans() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var count = context.getBean(DestructionCount.class);
            try (var scope = factory.createScope()) {
                scope.run(provider -> {
                    assertTrue(provider.getRequiredService(AnnotatedResource.class).initialized);
                    assertEquals(0, count.value.get());
                    return null;
                });
            }
            assertEquals(1, count.value.get());
        }
    }

    @Test
    void allCallbacksAreAttemptedEvenWhenOneFails() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var callbacks = new ArrayList<String>();
            var scope = factory.createScope();
            scope.run(provider -> {
                Scope spi = context.getBeanFactory().getRegisteredScope("operation");
                spi.registerDestructionCallback("first", () -> {
                    callbacks.add("first");
                    throw new IllegalArgumentException("first failed");
                });
                spi.registerDestructionCallback("second", () -> {
                    callbacks.add("second");
                    throw new IllegalStateException("second failed");
                });
                return null;
            });
            var failure = assertThrows(IllegalStateException.class, scope::close);
            assertEquals(List.of("second", "first"), callbacks);
            assertEquals("second failed", failure.getMessage());
            assertEquals("first failed", assertInstanceOf(IllegalArgumentException.class,
                failure.getSuppressed()[0]).getMessage());
            scope.close();
        }
    }

    @Test
    void scopeAndProviderCannotBeUsedByANonOwnerThread() throws InterruptedException {
        try (var context = newContext()) {
            var scope = context.getBean(ServiceScopeFactory.class).createScope();
            var error = new AtomicReference<Throwable>();
            scope.run(provider -> {
                var other = Thread.ofVirtual().start(() -> {
                    try {
                        provider.getRequiredService(ScopedState.class);
                    } catch (Throwable failure) {
                        error.set(failure);
                    }
                });
                try {
                    other.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                assertInstanceOf(IllegalStateException.class, error.get());
                return null;
            });
            error.set(null);
            var other = Thread.ofVirtual().start(() -> {
                try {
                    scope.close();
                } catch (Throwable failure) {
                    error.set(failure);
                }
            });
            other.join();
            assertInstanceOf(IllegalStateException.class, error.get());
            scope.close();
        }
    }

    @Test
    void failedBeanConstructionIsNotCachedAndNextAttemptCanSucceed() {
        try (var context = newContext()) {
            var factory = context.getBean(ServiceScopeFactory.class);
            var attempts = context.getBean(ConstructionAttempts.class);
            try (var scope = factory.createScope()) {
                scope.run(provider -> {
                    assertThrows(BeanCreationException.class,
                        () -> provider.getRequiredService(FlakyResource.class));
                    assertNotNull(provider.getRequiredService(FlakyResource.class));
                    assertEquals(2, attempts.value.get());
                    return null;
                });
            }
        }
    }

    private static AnnotationConfigApplicationContext newContext() {
        var context = new AnnotationConfigApplicationContext();
        context.register(OperationScopeConfiguration.class, TestBeans.class,
            ConstructorConsumerA.class, ConstructorConsumerB.class);
        context.refresh();
        return context;
    }

    @Component
    @org.springframework.context.annotation.Scope("operation")
    static final class ConstructorConsumerA {
        final ScopedState state;

        ConstructorConsumerA(ScopedState state) {
            this.state = state;
        }
    }

    @Component
    @org.springframework.context.annotation.Scope("operation")
    static final class ConstructorConsumerB {
        final ScopedState state;

        ConstructorConsumerB(ScopedState state) {
            this.state = state;
        }
    }

    record FirstService(ScopedState state, SingletonToken singleton) { }
    record SecondService(ScopedState state, SingletonToken singleton) { }
    record QualifiedService(String selection) { }
    static final class ScopedState { }
    static final class SingletonToken { }
    static final class DestructionCount { final AtomicInteger value = new AtomicInteger(); }
    static final class ConstructionAttempts { final AtomicInteger value = new AtomicInteger(); }
    static final class FlakyResource { }

    static final class AnnotatedResource {
        private final DestructionCount counter;
        boolean initialized;

        AnnotatedResource(DestructionCount counter) {
            this.counter = counter;
        }

        @PostConstruct
        void init() {
            initialized = true;
        }

        @PreDestroy
        void destroy() {
            counter.value.incrementAndGet();
        }
    }

    static final class TrackedResource implements DisposableBean {
        private final DestructionCount counter;

        TrackedResource(DestructionCount counter) {
            this.counter = counter;
        }

        @Override
        public void destroy() {
            counter.value.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean SingletonToken singletonToken() { return new SingletonToken(); }
        @Bean DestructionCount destructionCount() { return new DestructionCount(); }
        @Bean ConstructionAttempts constructionAttempts() { return new ConstructionAttempts(); }
        @Bean("chosen") String chosen() { return "chosen"; }
        @Bean("other") String other() { return "other"; }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        ScopedState scopedState() { return new ScopedState(); }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        FirstService firstService(ScopedState state, SingletonToken singleton) {
            return new FirstService(state, singleton);
        }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        SecondService secondService(ScopedState state, SingletonToken singleton) {
            return new SecondService(state, singleton);
        }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        QualifiedService qualifiedService(@Qualifier("chosen") String selection) {
            return new QualifiedService(selection);
        }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        TrackedResource trackedResource(DestructionCount counter) {
            return new TrackedResource(counter);
        }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        AnnotatedResource annotatedResource(DestructionCount counter) {
            return new AnnotatedResource(counter);
        }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        FlakyResource flakyResource(ConstructionAttempts attempts) {
            if (attempts.value.incrementAndGet() == 1) {
                throw new IllegalArgumentException("intentional first-construction failure");
            }
            return new FlakyResource();
        }
    }

}
