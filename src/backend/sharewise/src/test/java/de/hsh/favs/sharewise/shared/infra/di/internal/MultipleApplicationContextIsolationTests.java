package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

class MultipleApplicationContextIsolationTests {

    @Test
    void distinctContextsRetainTheirOwnBindingsAndSingletonsEvenWhenNestedOnOneThread() {
        try (AnnotationConfigApplicationContext first = newContext(FirstContext.class);
             AnnotationConfigApplicationContext second = newContext(SecondContext.class)) {
            ServiceScopeFactory firstFactory = first.getBean(ServiceScopeFactory.class);
            ServiceScopeFactory secondFactory = second.getBean(ServiceScopeFactory.class);
            OperationDispatcher firstDispatcher = first.getBean(OperationDispatcher.class);
            OperationDispatcher secondDispatcher = second.getBean(OperationDispatcher.class);

            assertNotSame(firstFactory, secondFactory);
            assertNotSame(firstDispatcher, secondDispatcher);
            assertNotSame(first.getBeanFactory().getRegisteredScope("operation"),
                second.getBeanFactory().getRegisteredScope("operation"));
            assertNotSame(first.getBean(ContextMarker.class), second.getBean(ContextMarker.class));

            try (ServiceScope firstScope = firstFactory.createScope()) {
                firstScope.run(firstProvider -> {
                    ContextService firstService = firstProvider.getRequiredService(ContextService.class);
                    assertEquals("first", firstService.marker().name());
                    assertSame(first.getBean(ContextMarker.class), firstService.marker());
                    assertThrows(BeanCreationException.class, () -> second.getBean(ContextService.class));
                    assertThrows(IllegalStateException.class,
                        () -> firstDispatcher.dispatch(ContextService.class, ignored -> null));

                    // The second application's dispatcher is independent of the first binding.
                    ContextState other = secondDispatcher.dispatch(ContextService.class, service -> {
                        assertEquals("second", service.marker().name());
                        assertSame(second.getBean(ContextMarker.class), service.marker());
                        assertNotSame(firstService.state(), service.state());
                        return service.state();
                    });

                    try (ServiceScope secondScope = secondFactory.createScope()) {
                        secondScope.run(secondProvider -> {
                            ContextService secondService = secondProvider.getRequiredService(ContextService.class);
                            assertEquals("second", secondService.marker().name());
                            assertNotSame(other, secondService.state());
                            assertNotSame(firstService.state(), secondService.state());
                            // Each bean factory has its own lexical key. B's binding does not shadow A's.
                            assertSame(firstService, firstProvider.getRequiredService(ContextService.class));
                            return null;
                        });
                    }
                    assertSame(firstService, firstProvider.getRequiredService(ContextService.class));
                    assertThrows(BeanCreationException.class, () -> second.getBean(ContextService.class));
                    return null;
                });
            }
            assertThrows(BeanCreationException.class, () -> first.getBean(ContextService.class));
        }
    }

    private static AnnotationConfigApplicationContext newContext(Class<?> markerConfiguration) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(OperationScopeConfiguration.class, SharedBeans.class, markerConfiguration);
        context.refresh();
        return context;
    }

    record ContextService(ContextMarker marker, ContextState state) { }
    record ContextMarker(String name) { }
    static final class ContextState { }

    @Configuration(proxyBeanMethods = false)
    static class SharedBeans {
        @Bean @Scope("operation")
        ContextState contextState() { return new ContextState(); }
        @Bean @Scope("operation")
        ContextService contextService(ContextMarker marker, ContextState state) {
            return new ContextService(marker, state);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FirstContext {
        @Bean ContextMarker contextMarker() { return new ContextMarker("first"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondContext {
        @Bean ContextMarker contextMarker() { return new ContextMarker("second"); }
    }
}
