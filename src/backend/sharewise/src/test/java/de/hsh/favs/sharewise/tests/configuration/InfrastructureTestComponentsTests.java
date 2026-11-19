package de.hsh.favs.sharewise.tests.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator;
import de.hsh.favs.sharewise.testsupport.configuration.InfrastructureTestComponents;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Lightweight infrastructure contexts must not require unrelated application dependencies. */
class InfrastructureTestComponentsTests {

    @Test
    void coreInfrastructureStartsWithoutADataSourceOrHealthChecks() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(InfrastructureTestComponents.class)) {
            assertNotNull(context.getBean(OperationDispatcher.class));
            assertNotNull(context.getBean(ServiceScopeFactory.class));
            assertEquals(0, context.getBeanNamesForType(ReadinessIndicator.class).length);
        }
    }
}
