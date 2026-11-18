package de.hsh.favs.sharewise.testsupport.configuration;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/** Production operation-scope wiring for lightweight tests with no persistence requirements. */
@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(
    basePackageClasses = OperationDispatcher.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestComponent.class)
)
public class OperationScopeTestComponents {
}
