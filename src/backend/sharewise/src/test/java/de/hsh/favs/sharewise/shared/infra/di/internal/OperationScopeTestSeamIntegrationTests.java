package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** Integration-style test callbacks use the same Spring operation scope as production dispatch. */
@SpringJUnitConfig({ OperationScopeConfiguration.class, OperationScopeTestSeamIntegrationTests.Fixtures.class })
class OperationScopeTestSeamIntegrationTests {

    @Autowired ServiceScopeFactory scopeFactory;
    @Autowired OperationDispatcher dispatcher;
    @Autowired LifecycleCounters lifecycle;

    @Test
    void testCallbackInspectsAnEntryAndItsCollaboratorsBeforeScopeTeardown() {
        int destroyedBefore = lifecycle.destroyed.get();
        OperationState firstState = new OperationTestHarness(scopeFactory).inOperationScope(scope -> {
            EntryService entry = scope.resolve(EntryService.class);
            WriterService writer = scope.resolve(WriterService.class);
            OperationState state = scope.resolve(OperationState.class);
            LifecycleResource resource = scope.resolve(LifecycleResource.class);

            assertSame(state, entry.state());
            assertSame(state, writer.state());
            assertSame(writer, entry.writer());
            assertSame(resource, entry.resource());
            assertTrue(resource.initialized);
            assertTrue(resource.postProcessed);
            assertEquals("preferred", entry.label().value());

            entry.write("one");
            writer.write("two");
            assertEquals(List.of("one", "two"), state.messages);
            assertEquals(destroyedBefore, lifecycle.destroyed.get());
            return state;
        });
        assertEquals(destroyedBefore + 1, lifecycle.destroyed.get());

        OperationState secondState = dispatcher.dispatch(EntryService.class, entry -> {
            assertTrue(entry.resource().initialized);
            assertTrue(entry.resource().postProcessed);
            assertTrue(entry.state().messages.isEmpty());
            entry.write("new operation");
            return entry.state();
        });
        assertNotSame(firstState, secondState);
        assertEquals(List.of("new operation"), secondState.messages);
        assertEquals(destroyedBefore + 2, lifecycle.destroyed.get());
    }

    @Test
    void prototypeBeansAreNotOwnedOrDestroyedByTheOperationScope() {
        int closedBefore = lifecycle.prototypesClosed.get();
        PrototypePair pair = new OperationTestHarness(scopeFactory).inOperationScope(scope -> new PrototypePair(
            scope.resolve(PrototypeResource.class),
            scope.resolve(PrototypeResource.class)));
        PrototypeResource first = pair.first();
        PrototypeResource second = pair.second();
        assertNotSame(first, second);
        assertEquals(closedBefore, lifecycle.prototypesClosed.get());
        // Spring's prototype ownership contract leaves disposal to the recipient.
        first.close();
        second.close();
        assertEquals(closedBefore + 2, lifecycle.prototypesClosed.get());
    }

    record EntryService(OperationState state, WriterService writer,
                        LifecycleResource resource, Label label) {
        void write(String message) { writer.write(message); }
    }

    record WriterService(OperationState state) {
        void write(String message) { state.messages.add(message); }
    }

    record Label(String value) { }
    record PrototypePair(PrototypeResource first, PrototypeResource second) { }

    static final class OperationState {
        final List<String> messages = new ArrayList<>();
    }

    static final class LifecycleCounters {
        final AtomicInteger destroyed = new AtomicInteger();
        final AtomicInteger prototypesClosed = new AtomicInteger();
    }

    static final class LifecycleResource {
        private final LifecycleCounters counters;
        boolean initialized;
        boolean postProcessed;

        LifecycleResource(LifecycleCounters counters) { this.counters = counters; }
        void initialize() { initialized = true; }
        void destroy() { counters.destroyed.incrementAndGet(); }
    }

    static final class PrototypeResource implements AutoCloseable {
        private final LifecycleCounters counters;
        PrototypeResource(LifecycleCounters counters) { this.counters = counters; }
        @Override public void close() { counters.prototypesClosed.incrementAndGet(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean LifecycleCounters lifecycleCounters() { return new LifecycleCounters(); }
        @Bean("preferred") Label preferredLabel() { return new Label("preferred"); }
        @Bean("other") Label otherLabel() { return new Label("other"); }

        @Bean @Scope("operation")
        OperationState operationState() { return new OperationState(); }

        @Bean @Scope("operation")
        WriterService writerService(OperationState state) { return new WriterService(state); }

        @Bean @Scope("operation")
        EntryService entryService(OperationState state, WriterService writer,
                LifecycleResource resource, @Qualifier("preferred") Label label) {
            return new EntryService(state, writer, resource, label);
        }

        @Bean(initMethod = "initialize", destroyMethod = "destroy")
        @Scope("operation")
        LifecycleResource lifecycleResource(LifecycleCounters counters) {
            return new LifecycleResource(counters);
        }

        @Bean @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        PrototypeResource prototypeResource(LifecycleCounters counters) {
            return new PrototypeResource(counters);
        }

        @Bean
        static BeanPostProcessor resourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof LifecycleResource resource) {
                        resource.postProcessed = true;
                    }
                    return bean;
                }
            };
        }
    }
}
