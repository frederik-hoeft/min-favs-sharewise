package de.hsh.favs.sharewise.testsupport.configuration;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Core operation/transaction/persistence discovery for lightweight, non-Boot Spring tests.
 * Deliberately omits unrelated application infrastructure (e.g. health checks),
 * which can require dependencies those tests do not provide.
 * Full-application tests instead use ShareWiseApplication's normal Boot component scan.
 * Test-only components must be opted into explicitly by the consuming test.
 */
@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(
    basePackageClasses = {OperationDispatcher.class, Transactions.class, JooqAccess.class, JpaAccess.class},
    excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestComponent.class)
)
public class InfrastructureTestComponents {
}
