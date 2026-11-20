package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

class OperationInputInitializationTests {

    @Test
    void initializesOneScopedTargetFromMultipleInputsBeforeConstructingConsumers() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            Events events = context.getBean(Events.class);
            AtomicReference<OperationInputRegistry> escapedRegistry = new AtomicReference<>();
            InputConfiguration first = dispatcher.dispatch(inputs -> {
                escapedRegistry.set(inputs);
                assertSame(inputs, inputs.add(CharSequence.class, "first").add(Integer.class, 42));
            }, Entry.class, service -> {
                assertSame(service.left.configuration(), service.right.configuration());
                assertEquals("first", service.left.configuration().name());
                assertEquals(42, service.right.configuration().number());
                assertEquals(List.of("min", "rejected-early-read", "zero", "max", "construct-entry"),
                    events.history.subList(0, 5));
                return service.left.configuration();
            });
            assertThrows(IllegalStateException.class, () -> escapedRegistry.get().add(String.class, "late"));
            assertEquals(1, events.destroyed.get());

            InputConfiguration second = dispatcher.dispatch(Entry.class, service -> {
                assertEquals("anonymous", service.left.configuration().name());
                assertEquals(-1, service.right.configuration().number());
                return service.left.configuration();
            });
            assertNotSame(first, second);
            assertEquals(2, events.destroyed.get());
            assertEquals(2, events.created.get());
        }
    }

    @Test
    void registrationRejectsDuplicateNullAndMismatchedValuesBeforeScopeActivation() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            Events events = context.getBean(Events.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(inputs -> {
                inputs.add(String.class, "first");
                inputs.add(String.class, "second");
            }, Entry.class, service -> null));
            assertThrows(NullPointerException.class, () -> dispatcher.dispatch(inputs ->
                inputs.add(String.class, null), Entry.class, service -> null));
            assertThrows(NullPointerException.class, () -> dispatcher.dispatch(inputs ->
                inputs.add(null, "value"), Entry.class, service -> null));
            assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(inputs -> {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Class rawType = Integer.class;
                inputs.add(rawType, "not an integer");
            }, Entry.class, service -> null));
            assertEquals(0, events.created.get());
            dispatcher.dispatch(Entry.class, service -> null);
            assertEquals(1, events.created.get());
        }
    }

    @Test
    void registeredKeysAreExactAndDoNotCreateSpringBeans() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            dispatcher.dispatch(inputs -> inputs.add(String.class, "not a CharSequence-keyed input"), Entry.class,
                service -> {
                    assertEquals("anonymous", service.left.configuration().name());
                    assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                        () -> context.getBean(String.class));
                    return null;
                });
        }
    }

    @Test
    void initializerCopiesCallerMutableDataBeforePublishingScopedState() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            StringBuilder mutableInput = new StringBuilder("original");
            dispatcher.dispatch(inputs -> inputs.add(CharSequence.class, mutableInput), Entry.class, service -> {
                mutableInput.replace(0, mutableInput.length(), "changed");
                assertEquals("original", service.left.configuration().name());
                return null;
            });
        }
    }

    @Test
    void registrationRemainsThreadConfinedEvenBeforeScopeActivation() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            dispatcher.dispatch(inputs -> {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread worker = Thread.ofVirtual().start(() -> {
                    try {
                        inputs.add(CharSequence.class, "wrong thread");
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                });
                try {
                    worker.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                assertInstanceOf(IllegalStateException.class, failure.get());
                inputs.add(CharSequence.class, "owner");
            }, Entry.class, service -> {
                assertEquals("owner", service.left.configuration().name());
                return null;
            });
        }
    }

    @Test
    void requiredInputIsDecidedByInitializerAndFailureCleansUpWithoutEntry() {
        try (AnnotationConfigApplicationContext context = newContext(RequiredInput.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            Events events = context.getBean(Events.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(Entry.class, service -> null));
            assertEquals(1, events.created.get());
            assertEquals(1, events.destroyed.get());
            assertFalse(events.history.contains("construct-entry"));
            dispatcher.dispatch(inputs -> inputs.add(CharSequence.class, "accepted"), Entry.class, service -> {
                assertEquals("accepted", service.left.configuration().name());
                return null;
            });
            assertEquals(2, events.destroyed.get());
        }
    }

    @Test
    void configurationsRejectEarlyReadsAndMutationsAfterFreezing() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            InputConfiguration configuration = dispatcher.dispatch(inputs -> inputs.add(CharSequence.class, "ready"),
                Entry.class, service -> service.left.configuration());
            assertEquals("ready", configuration.name());
            assertThrows(IllegalStateException.class, () -> configuration.setName("too late"));
            assertThrows(IllegalStateException.class, configuration::finishInitialization);
            assertTrue(context.getBean(Events.class).history.contains("rejected-early-read"));
        }
    }

    @Test
    void conflictingContributionsPreventEntryConstructionAndRunDestruction() {
        try (AnnotationConfigApplicationContext context = newContext(InvalidConfiguration.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            Events events = context.getBean(Events.class);
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(Entry.class, service -> null));
            assertEquals(1, events.destroyed.get());
            assertFalse(events.history.contains("construct-entry"));
        }
    }

    @Test
    void finalizationValidationFailurePreventsEntryConstructionAndDisposesTarget() {
        try (AnnotationConfigApplicationContext context = newContext(FinalValidationFailure.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            Events events = context.getBean(Events.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(Entry.class, service -> null));
            assertFalse(events.history.contains("construct-entry"));
            assertEquals(1, events.destroyed.get());
        }
    }

    @Test
    void equalPriorityContributionsRemainIndependentAndConflictsFailClosed() {
        try (AnnotationConfigApplicationContext context = newContext(EqualPriority.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            dispatcher.dispatch(inputs -> {
                inputs.add(CharSequence.class, "first");
                inputs.add(Integer.class, 7);
            }, Entry.class, service -> {
                assertEquals("first", service.left.configuration().name());
                assertEquals(7, service.right.configuration().number());
                return null;
            });
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(inputs -> {
                inputs.add(CharSequence.class, "first");
                inputs.add(Integer.class, 7);
                inputs.add(Long.class, 99L);
            }, Entry.class, service -> null));
        }
    }

    @Test
    void singletonAndPrototypeTargetsAreRejectedWithoutResolvingThem() {
        for (Class<?> fixture : List.of(SingletonTarget.class, PrototypeTarget.class)) {
            try (AnnotationConfigApplicationContext context = newContext(fixture)) {
                OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
                Events events = context.getBean(Events.class);
                int before = events.created.get();
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> dispatcher.dispatch(BadEntry.class, service -> null));
                assertTrue(failure.getMessage().contains("operation scope"));
                assertEquals(before, events.created.get());
            }
        }
    }

    @Test
    void initializerFailurePreservesPrimaryExceptionWhenScopedCleanupAlsoFails() {
        try (AnnotationConfigApplicationContext context = newContext(FailingInitializer.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            Events events = context.getBean(Events.class);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(Entry.class, service -> null));
            assertEquals("initializer failed", failure.getMessage());
            assertEquals(1, events.destroyed.get());
            assertFalse(events.history.contains("construct-entry"));
            assertEquals("cleanup failed", assertInstanceOf(IllegalStateException.class,
                failure.getSuppressed()[0]).getMessage());
        }
    }

    @Test
    void recursiveDispatchInRegistrationOrInitializationIsRejected() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(inputs ->
                dispatcher.dispatch(Entry.class, service -> null), Entry.class, service -> null));
            dispatcher.dispatch(Entry.class, service -> null);
        }
        try (AnnotationConfigApplicationContext context = newContext(RecursiveInitializer.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(Entry.class, service -> null));
        }
    }

    @Test
    void nestedLowLevelScopesDoNotInheritInputsOrInitializedConfigurations() {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            ServiceScopeFactory scopes = context.getBean(ServiceScopeFactory.class);
            dispatcher.dispatch(inputs -> inputs.add(CharSequence.class, "outer"), Entry.class, service -> {
                assertEquals("outer", service.left.configuration().name());
                try (ServiceScope child = scopes.createScope()) {
                    child.run(provider -> {
                        InputConfiguration childConfiguration = provider.getRequiredService(InputConfiguration.class);
                        assertNotSame(service.left.configuration(), childConfiguration);
                        assertThrows(IllegalStateException.class, childConfiguration::name);
                        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(Entry.class, ignored -> null));
                        return null;
                    });
                }
                assertEquals("outer", service.left.configuration().name());
                return null;
            });
        }
    }

    @Test
    void concurrentDispatchInputsRemainIndependent() throws Exception {
        try (AnnotationConfigApplicationContext context = newContext(ComposedInputs.class);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            OperationDispatcher dispatcher = context.getBean(OperationDispatcher.class);
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (String name : List.of("alice", "bob")) {
                futures.add(executor.submit(() -> dispatcher.dispatch(inputs -> inputs.add(CharSequence.class, name),
                    Entry.class, service -> {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting for concurrent dispatch");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return service.left.configuration().name();
                    })));
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals("alice", futures.get(0).get(10, TimeUnit.SECONDS));
            assertEquals("bob", futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    private static AnnotationConfigApplicationContext newContext(Class<?> fixtures) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(OperationScopeConfiguration.class, CommonBeans.class, fixtures);
        context.refresh();
        return context;
    }

    record Entry(Left left, Right right) { }
    record Left(InputConfiguration configuration) { }
    record Right(InputConfiguration configuration) { }
    record BadEntry(BadConfiguration configuration) { }

    static final class Events {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        final List<String> history = Collections.synchronizedList(new ArrayList<>());
    }

    static final class InputConfiguration extends OperationScopedConfiguration {
        private final Events events;
        private String name;
        private int number = -1;

        InputConfiguration(Events events) {
            this.events = events;
            events.created.incrementAndGet();
        }

        void setName(String value) {
            requireInitializing();
            if (name != null) {
                throw new IllegalStateException("Name already initialized");
            }
            name = value;
        }

        void setNumber(int value) {
            requireInitializing();
            if (number != -1) {
                throw new IllegalStateException("Number already initialized");
            }
            number = value;
        }

        String name() {
            requireReady();
            return name;
        }

        int number() {
            requireReady();
            return number;
        }

        @Override
        protected void validate() {
            if (name == null) {
                name = "anonymous";
            }
        }

        @PreDestroy
        void destroy() {
            events.destroyed.incrementAndGet();
        }
    }

    static final class BadConfiguration extends OperationScopedConfiguration {
        BadConfiguration(Events events) {
            events.created.incrementAndGet();
        }
    }

    static final class ValidatedConfiguration extends OperationScopedConfiguration {
        private final Events events;

        ValidatedConfiguration(Events events) {
            this.events = events;
        }

        @Override
        protected void validate() {
            throw new IllegalArgumentException("invalid finalized configuration");
        }

        @PreDestroy
        void destroy() {
            events.destroyed.incrementAndGet();
        }
    }

    record TestInitializer<C extends OperationScopedConfiguration, D>(
        Class<C> configurationType,
        Class<D> inputType,
        int priority,
        BiConsumer<C, Optional<D>> action
    ) implements ScopedServiceInitializer<C, D> {

        @Override
        public void initialize(C configuration, Optional<D> input) {
            action.accept(configuration, input);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CommonBeans {
        @Bean Events events() { return new Events(); }
        @Bean @Scope("operation") InputConfiguration inputConfiguration(Events events) {
            return new InputConfiguration(events);
        }
        @Bean @Scope("operation") Entry entry(Left left, Right right, Events events) {
            events.history.add("construct-entry");
            return new Entry(left, right);
        }
        @Bean @Scope("operation") Left left(InputConfiguration configuration) {
            return new Left(configuration);
        }
        @Bean @Scope("operation") Right right(InputConfiguration configuration) {
            return new Right(configuration);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ComposedInputs {
        @Bean ScopedServiceInitializer<InputConfiguration, CharSequence> primary(Events events) {
            return new TestInitializer<>(InputConfiguration.class, CharSequence.class, Integer.MIN_VALUE,
                (configuration, input) -> {
                    events.history.add("min");
                    assertThrows(IllegalStateException.class, configuration::name);
                    events.history.add("rejected-early-read");
                    configuration.setName(input.map(CharSequence::toString).orElse("anonymous"));
                });
        }
        @Bean ScopedServiceInitializer<InputConfiguration, Integer> secondary(Events events) {
            return new TestInitializer<>(InputConfiguration.class, Integer.class, 0,
                (configuration, input) -> {
                    events.history.add("zero");
                    configuration.setNumber(input.orElse(-1));
                });
        }
        @Bean ScopedServiceInitializer<InputConfiguration, Long> last(Events events) {
            return new TestInitializer<>(InputConfiguration.class, Long.class, Integer.MAX_VALUE,
                (configuration, input) -> {
                    events.history.add("max");
                    assertTrue(input.isEmpty());
                });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequiredInput {
        @Bean ScopedServiceInitializer<InputConfiguration, CharSequence> required() {
            return new TestInitializer<>(InputConfiguration.class, CharSequence.class, 0,
                (configuration, input) -> configuration.setName(input.orElseThrow(
                    () -> new IllegalArgumentException("required input missing")).toString()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InvalidConfiguration {
        @Bean ScopedServiceInitializer<InputConfiguration, String> invalid() {
            return new TestInitializer<>(InputConfiguration.class, String.class, 0,
                (configuration, input) -> configuration.setName("first"));
        }
        @Bean ScopedServiceInitializer<InputConfiguration, Long> conflict() {
            return new TestInitializer<>(InputConfiguration.class, Long.class, 1,
                (configuration, input) -> configuration.setName("second"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FinalValidationFailure {
        @Bean @Scope("operation") ValidatedConfiguration validated(Events events) {
            return new ValidatedConfiguration(events);
        }
        @Bean ScopedServiceInitializer<ValidatedConfiguration, String> failValidation() {
            return new TestInitializer<>(ValidatedConfiguration.class, String.class, 0,
                (configuration, input) -> { });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EqualPriority {
        @Bean ScopedServiceInitializer<InputConfiguration, CharSequence> name() {
            return new TestInitializer<>(InputConfiguration.class, CharSequence.class, 2,
                (configuration, input) -> configuration.setName(input.orElseThrow().toString()));
        }
        @Bean ScopedServiceInitializer<InputConfiguration, Integer> number() {
            return new TestInitializer<>(InputConfiguration.class, Integer.class, 2,
                (configuration, input) -> configuration.setNumber(input.orElseThrow()));
        }
        @Bean ScopedServiceInitializer<InputConfiguration, Long> conflict() {
            return new TestInitializer<>(InputConfiguration.class, Long.class, 2,
                (configuration, input) -> input.ifPresent(value -> configuration.setNumber(value.intValue())));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SingletonTarget {
        @Bean BadConfiguration badConfiguration(Events events) {
            // A singleton target is not supposed to be instantiated during dispatch.
            return new BadConfiguration(events);
        }
        @Bean @Scope("operation") BadEntry badEntry(BadConfiguration configuration) {
            return new BadEntry(configuration);
        }
        @Bean ScopedServiceInitializer<BadConfiguration, String> bad() {
            return new TestInitializer<>(BadConfiguration.class, String.class, 0,
                (configuration, input) -> { });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrototypeTarget {
        @Bean @Scope("prototype") BadConfiguration badConfiguration(Events events) {
            return new BadConfiguration(events);
        }
        @Bean @Scope("operation") BadEntry badEntry(BadConfiguration configuration) {
            return new BadEntry(configuration);
        }
        @Bean ScopedServiceInitializer<BadConfiguration, String> bad() {
            return new TestInitializer<>(BadConfiguration.class, String.class, 0,
                (configuration, input) -> { });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingInitializer {
        @Bean ScopedServiceInitializer<InputConfiguration, String> fails(
            Events events, org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory
        ) {
            return new TestInitializer<>(InputConfiguration.class, String.class, 0,
                (configuration, input) -> {
                    // The scope's own lifecycle cleanup still happens when initialization throws.
                    events.history.add("init-failed");
                    beanFactory.getRegisteredScope("operation").registerDestructionCallback("fails", () -> {
                        throw new IllegalStateException("cleanup failed");
                    });
                    throw new IllegalArgumentException("initializer failed");
                });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecursiveInitializer {
        @Bean ScopedServiceInitializer<InputConfiguration, String> recurse(OperationDispatcher dispatcher) {
            return new TestInitializer<>(InputConfiguration.class, String.class, 0,
                (configuration, input) -> dispatcher.dispatch(Entry.class, service -> null));
        }
    }
}
