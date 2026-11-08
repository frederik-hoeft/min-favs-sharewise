package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.Scope;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OperationDispatcherIntegrationTests {

    @Test
    void dispatchCreatesOneSpringGraphPerOperationAndSharesOnlySingletons() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            SingletonMarker singleton = context.getBean(SingletonMarker.class);
            OperationState first = dispatcher.dispatch(EntryService.class, service -> {
                assertSame(service.left.state(), service.right.state());
                assertSame(singleton, service.singleton);
                service.left.state().calls.incrementAndGet();
                assertEquals(1, service.right.state().calls.get());
                return service.left.state();
            });
            OperationState second = dispatcher.dispatch(EntryService.class, service -> {
                assertSame(singleton, service.singleton);
                assertEquals(0, service.left.state().calls.get());
                return service.left.state();
            });
            assertNotSame(first, second);
        }
    }

    @Test
    void recursiveDispatchIsRejectedWithoutConstructingAnotherEntryService() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            ServiceScopeFactory scopes = context.getBean(ServiceScopeFactory.class);
            AtomicInteger creations = context.getBean(ConstructionCounter.class).value;
            dispatcher.dispatch(EntryService.class, service -> {
                assertEquals(1, creations.get());
                assertThrows(IllegalStateException.class,
                    () -> dispatcher.dispatch(EntryService.class, ignored -> null));
                assertEquals(1, creations.get());
                // Low-level nested DI scopes remain legal. They still cannot dispatch operations.
                try (ServiceScope child = scopes.createScope()) {
                    child.run(provider -> {
                        assertNotSame(service.left.state(), provider.getRequiredService(OperationState.class));
                        assertThrows(IllegalStateException.class,
                            () -> dispatcher.dispatch(EntryService.class, ignored -> null));
                        return null;
                    });
                }
                assertEquals(1, creations.get());
                return null;
            });
            try (ServiceScope manual = scopes.createScope()) {
                manual.run(provider -> {
                    assertThrows(IllegalStateException.class,
                        () -> dispatcher.dispatch(EntryService.class, ignored -> null));
                    assertEquals(1, creations.get());
                    return null;
                });
            }
            dispatcher.dispatch(EntryService.class, service -> null);
            assertEquals(2, creations.get());
        }
    }

    @Test
    void failureInBusinessCallbackPreservesOriginalWhenDestructionAlsoFails() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            List<String> callbacks = new ArrayList<>();
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(EntryService.class, service -> {
                    Scope spi = context.getBeanFactory().getRegisteredScope("operation");
                    spi.registerDestructionCallback("first", () -> {
                        callbacks.add("first");
                        throw new IllegalStateException("cleanup one");
                    });
                    spi.registerDestructionCallback("second", () -> {
                        callbacks.add("second");
                        throw new IllegalStateException("cleanup two");
                    });
                    throw new IllegalArgumentException("operation failed");
                }));
            assertEquals("operation failed", failure.getMessage());
            assertEquals(List.of("second", "first"), callbacks);
            IllegalStateException cleanup = assertInstanceOf(IllegalStateException.class, failure.getSuppressed()[0]);
            assertEquals("cleanup two", cleanup.getMessage());
            assertEquals("cleanup one", cleanup.getSuppressed()[0].getMessage());
            // Both the lexical binding and the cache from the failed dispatch are gone.
            dispatcher.dispatch(EntryService.class, service -> null);
        }
    }

    @Test
    void destructionFailureAfterSuccessfulCallbackIsReportedAndNextDispatchWorks() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(EntryService.class, service -> {
                    Scope spi = context.getBeanFactory().getRegisteredScope("operation");
                    spi.registerDestructionCallback("bad", () -> {
                        throw new IllegalStateException("cleanup failed");
                    });
                    return null;
                }));
            assertEquals("cleanup failed", failure.getMessage());
            dispatcher.dispatch(EntryService.class, service -> null);
        }
    }

    @Test
    void entryServiceConstructionFailureClosesAlreadyConstructedScopedDependencies() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            DestructionCounter counter = context.getBean(DestructionCounter.class);
            assertThrows(BeanCreationException.class,
                () -> dispatcher.dispatch(FailingEntry.class, service -> null));
            assertEquals(1, counter.value.get());
            dispatcher.dispatch(EntryService.class, service -> null);
        }
    }

    @Test
    void cleanupCannotResolveScopedBeansOrReopenTheFinishedOperation() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            AtomicInteger rejected = new AtomicInteger();
            dispatcher.dispatch(EntryService.class, service -> {
                Scope spi = context.getBeanFactory().getRegisteredScope("operation");
                spi.registerDestructionCallback("check", () -> {
                    assertThrows(BeanCreationException.class, () -> context.getBean(OperationState.class));
                    assertThrows(IllegalStateException.class,
                        () -> dispatcher.dispatch(EntryService.class, ignored -> null));
                    rejected.incrementAndGet();
                });
                return null;
            });
            assertEquals(1, rejected.get());
        }
    }

    @Test
    void independentConcurrentDispatchesUseDifferentBeans() throws Exception {
        try (AnnotationConfigApplicationContext context = newContext();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<OperationState>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> dispatcher.dispatch(EntryService.class, service -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for concurrent operation");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    assertSame(service.left.state(), service.right.state());
                    return service.left.state();
                })));
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            OperationState first = futures.get(0).get(10, TimeUnit.SECONDS);
            OperationState second = futures.get(1).get(10, TimeUnit.SECONDS);
            assertNotSame(first, second);
        }
    }

    @Test
    void independentVirtualThreadDispatchUsesItsOwnScope() throws InterruptedException {
        try (AnnotationConfigApplicationContext context = newContext()) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            OperationState ownerState = dispatcher.dispatch(EntryService.class, service -> service.left.state());
            AtomicReference<OperationState> workerState = new AtomicReference<>();
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    workerState.set(dispatcher.dispatch(EntryService.class, service -> service.left.state()));
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            worker.join();
            assertNull(workerFailure.get(), () -> "Virtual-thread dispatch failed: " + workerFailure.get());
            assertNotSame(ownerState, workerState.get());
        }
    }

    @Test
    void wrongThreadScopeActivationIsRejectedAndOwnerCanStillUseIt() throws Exception {
        try (AnnotationConfigApplicationContext context = newContext()) {
            ServiceScope scope = context.getBean(ServiceScopeFactory.class).createScope();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Throwable> otherThread = executor.submit(() -> {
                    try {
                        scope.run(provider -> null);
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                assertInstanceOf(IllegalStateException.class, otherThread.get(10, TimeUnit.SECONDS));
            }
            scope.run(provider -> {
                assertSame(provider.getRequiredService(OperationState.class),
                    provider.getRequiredService(OperationState.class));
                return null;
            });
            scope.close();
        }
    }

    private static AnnotationConfigApplicationContext newContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(OperationScopeConfiguration.class, Beans.class);
        context.refresh();
        return context;
    }

    record EntryService(Left left, Right right, SingletonMarker singleton) { }
    record Left(OperationState state) { }
    record Right(OperationState state) { }
    static final class OperationState { final AtomicInteger calls = new AtomicInteger(); }
    static final class SingletonMarker { }
    static final class ConstructionCounter { final AtomicInteger value = new AtomicInteger(); }
    static final class DestructionCounter { final AtomicInteger value = new AtomicInteger(); }
    record FailingEntry(TrackedBean dependency) { }

    static final class TrackedBean {
        private final DestructionCounter counter;

        TrackedBean(DestructionCounter counter) {
            this.counter = counter;
        }

        @PreDestroy
        void destroy() {
            counter.value.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean SingletonMarker singletonMarker() { return new SingletonMarker(); }
        @Bean ConstructionCounter constructionCounter() { return new ConstructionCounter(); }
        @Bean DestructionCounter destructionCounter() { return new DestructionCounter(); }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        OperationState operationState() { return new OperationState(); }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        Left left(OperationState state) { return new Left(state); }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        Right right(OperationState state) { return new Right(state); }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        EntryService entryService(Left left, Right right, SingletonMarker singleton, ConstructionCounter counter) {
            counter.value.incrementAndGet();
            return new EntryService(left, right, singleton);
        }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        TrackedBean trackedBean(DestructionCounter counter) { return new TrackedBean(counter); }

        @Bean
        @org.springframework.context.annotation.Scope("operation")
        FailingEntry failingEntry(TrackedBean trackedBean) {
            throw new IllegalArgumentException("entry construction failed");
        }
    }
}
