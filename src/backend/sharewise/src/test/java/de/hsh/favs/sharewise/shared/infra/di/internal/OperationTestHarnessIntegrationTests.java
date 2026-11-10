package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.Scope;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OperationTestHarnessIntegrationTests {

    @Test
    void resolvesSharedScopedCollaboratorsAndDestroysEachOwnedBeanOnce() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationTestHarness harness = new OperationTestHarness(context.getBean(ServiceScopeFactory.class));
            LifecycleCount lifetime = context.getBean(LifecycleCount.class);
            int before = lifetime.destroyed.get();
            State first = harness.inOperationScope(scope -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                Entry entry = scope.resolve(Entry.class);
                assertSame(entry.state(), scope.resolve(State.class));
                assertSame(entry.state(), scope.resolve(Reader.class).state());
                assertSame(entry.resource(), scope.resolve(Resource.class));
                assertSame(context.getBean(SingletonToken.class), entry.singleton());
                assertEquals(before, lifetime.destroyed.get());
                return entry.state();
            });
            assertEquals(before + 1, lifetime.destroyed.get());

            State second = harness.inOperationScope(scope -> scope.resolve(Entry.class).state());
            assertNotSame(first, second);
            assertEquals(before + 2, lifetime.destroyed.get());
        }
    }

    @Test
    void retainedTestScopeAndWrongThreadResolutionAreRejected() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationTestHarness harness = new OperationTestHarness(context.getBean(ServiceScopeFactory.class));
            AtomicReference<OperationTestScope> retained = new AtomicReference<>();
            AtomicReference<Throwable> wrongThreadFailure = new AtomicReference<>();
            harness.inOperationScope(scope -> {
                retained.set(scope);
                assertSame(scope.resolve(State.class), scope.resolve(State.class));
                Thread worker = Thread.ofVirtual().start(() -> {
                    try {
                        scope.resolve(State.class);
                    } catch (Throwable failure) {
                        wrongThreadFailure.set(failure);
                    }
                });
                try {
                    worker.join();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                assertInstanceOf(IllegalStateException.class, wrongThreadFailure.get());
                assertSame(scope.resolve(State.class), scope.resolve(State.class));
                return null;
            });
            assertThrows(IllegalStateException.class, () -> retained.get().resolve(State.class));
            // Even singleton resolution through an escaped provider must be rejected.
            assertThrows(IllegalStateException.class, () -> retained.get().resolve(SingletonToken.class));
        }
    }

    @Test
    void independentNestedScopesRestoreTheOuterBindingAfterAnInnerFailure() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationTestHarness harness = new OperationTestHarness(context.getBean(ServiceScopeFactory.class));
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            harness.inOperationScope(outer -> {
                State outerState = outer.resolve(State.class);
                AtomicReference<OperationTestScope> retainedInner = new AtomicReference<>();
                IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> harness.inOperationScope(inner -> {
                        retainedInner.set(inner);
                        assertNotSame(outerState, inner.resolve(State.class));
                        assertThrows(IllegalStateException.class, () -> outer.resolve(State.class));
                        assertThrows(IllegalStateException.class,
                            () -> dispatcher.dispatch(Entry.class, entry -> null));
                        throw new IllegalArgumentException("inner failed");
                    }));
                assertEquals("inner failed", thrown.getMessage());
                assertThrows(IllegalStateException.class, () -> retainedInner.get().resolve(State.class));
                assertSame(outerState, outer.resolve(State.class));
                return null;
            });
        }
    }

    @Test
    void callbackAndDestructionFailuresRetainThePrimaryFailureAndAttemptAllCleanup() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationTestHarness harness = new OperationTestHarness(context.getBean(ServiceScopeFactory.class));
            LifecycleCount lifetime = context.getBean(LifecycleCount.class);
            int before = lifetime.destroyed.get();
            List<String> destroyed = new ArrayList<>();
            IllegalArgumentException callbackFailure = new IllegalArgumentException("callback failed");
            IllegalStateException lastFailure = new IllegalStateException("last registered failed");
            IllegalArgumentException firstFailure = new IllegalArgumentException("first registered failed");

            IllegalArgumentException observed = assertThrows(IllegalArgumentException.class,
                () -> harness.inOperationScope(scope -> {
                    scope.resolve(Resource.class);
                    Scope springScope = context.getBeanFactory().getRegisteredScope("operation");
                    springScope.registerDestructionCallback("first", () -> {
                        destroyed.add("first");
                        throw firstFailure;
                    });
                    springScope.registerDestructionCallback("last", () -> {
                        destroyed.add("last");
                        throw lastFailure;
                    });
                    throw callbackFailure;
                }));

            assertSame(callbackFailure, observed);
            assertEquals(List.of("last", "first"), destroyed);
            assertEquals(before + 1, lifetime.destroyed.get());
            assertEquals(1, observed.getSuppressed().length);
            assertSame(lastFailure, observed.getSuppressed()[0]);
            assertEquals(1, lastFailure.getSuppressed().length);
            assertSame(firstFailure, lastFailure.getSuppressed()[0]);

            // A failure during teardown cannot poison the next independent scope.
            harness.inOperationScope(scope -> {
                scope.resolve(Resource.class);
                return null;
            });
            assertEquals(before + 2, lifetime.destroyed.get());
        }
    }

    @Test
    void failedEntryConstructionStillDestroysSuccessfullyCreatedDependencies() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationTestHarness harness = new OperationTestHarness(context.getBean(ServiceScopeFactory.class));
            LifecycleCount lifetime = context.getBean(LifecycleCount.class);
            int before = lifetime.destroyed.get();
            assertThrows(BeanCreationException.class,
                () -> harness.inOperationScope(scope -> scope.resolve(FailingEntry.class)));
            assertEquals(before + 1, lifetime.destroyed.get());
        }
    }

    @Test
    void destructionFailurePropagatesWhenTheCallbackSucceeds() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationTestHarness harness = new OperationTestHarness(context.getBean(ServiceScopeFactory.class));
            IllegalStateException failure = new IllegalStateException("teardown failed");
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> harness.inOperationScope(scope -> {
                    Scope springScope = context.getBeanFactory().getRegisteredScope("operation");
                    springScope.registerDestructionCallback("broken", () -> { throw failure; });
                    return null;
                }));
            assertSame(failure, thrown);
        }
    }

    @Test
    void separateSpringContextsKeepIndependentScopeBindingsAndBeanGraphs() {
        try (AnnotationConfigApplicationContext first = newContext();
             AnnotationConfigApplicationContext second = newContext()) {
            OperationTestHarness firstHarness = new OperationTestHarness(first.getBean(ServiceScopeFactory.class));
            OperationTestHarness secondHarness = new OperationTestHarness(second.getBean(ServiceScopeFactory.class));
            assertNotSame(first.getBean(SingletonToken.class), second.getBean(SingletonToken.class));
            firstHarness.inOperationScope(firstScope -> {
                State firstState = firstScope.resolve(State.class);
                secondHarness.inOperationScope(secondScope -> {
                    assertNotSame(firstState, secondScope.resolve(State.class));
                    // Unlike nested scopes within one factory, independent contexts have different bindings.
                    assertSame(firstState, firstScope.resolve(State.class));
                    return null;
                });
                assertSame(firstState, firstScope.resolve(State.class));
                return null;
            });
        }
    }

    private static AnnotationConfigApplicationContext newContext() {
        AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.register(OperationScopeConfiguration.class, Fixtures.class);
        applicationContext.refresh();
        return applicationContext;
    }

    record Entry(State state, Reader reader, Resource resource, SingletonToken singleton) { }
    record FailingEntry(Resource resource) { }
    record Reader(State state) { }
    static final class State { }
    static final class SingletonToken { }
    static final class LifecycleCount { final AtomicInteger destroyed = new AtomicInteger(); }
    static final class Resource {
        private final LifecycleCount count;
        Resource(LifecycleCount count) { this.count = count; }
        void destroy() { count.destroyed.incrementAndGet(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean SingletonToken singletonToken() { return new SingletonToken(); }
        @Bean LifecycleCount lifecycleCount() { return new LifecycleCount(); }
        @Bean @org.springframework.context.annotation.Scope("operation")
        State state() { return new State(); }
        @Bean @org.springframework.context.annotation.Scope("operation")
        Reader reader(State state) { return new Reader(state); }
        @Bean @org.springframework.context.annotation.Scope("operation")
        Entry entry(State state, Reader reader, Resource resource, SingletonToken singleton) {
            return new Entry(state, reader, resource, singleton);
        }
        @Bean(destroyMethod = "destroy") @org.springframework.context.annotation.Scope("operation")
        Resource resource(LifecycleCount count) { return new Resource(count); }
        @Bean @org.springframework.context.annotation.Scope("operation")
        FailingEntry failingEntry(Resource resource) {
            throw new IllegalArgumentException("construction failed");
        }
    }
}
