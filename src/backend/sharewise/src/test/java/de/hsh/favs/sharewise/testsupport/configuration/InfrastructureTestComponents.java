package de.hsh.favs.sharewise.testsupport.configuration;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Production infrastructure discovery for lightweight, non-Boot Spring integration tests.
 * Full-application tests instead use ShareWiseApplication's normal Boot component scan.
 * Test-only components must be opted into explicitly by the consuming test.
 */
@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = "de.hsh.favs.sharewise.shared.infra",
    excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestComponent.class)
)
public class InfrastructureTestComponents {
}
