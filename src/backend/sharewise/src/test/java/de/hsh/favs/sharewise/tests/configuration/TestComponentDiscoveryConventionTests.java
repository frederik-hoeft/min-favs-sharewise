package de.hsh.favs.sharewise.tests.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.hsh.favs.sharewise.testsupport.configuration.InfrastructureTestComponents;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/** Prevents test-local beans from silently entering unrelated Spring application contexts. */
class TestComponentDiscoveryConventionTests {

    @Test
    void testSourcesUseOnlyExplicitTestStereotypesAndCentralizedInfrastructureScanning() {
        JavaClasses testClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.OnlyIncludeTests())
            .importPackages("de.hsh.favs.sharewise");
        List<String> violations = new ArrayList<>();
        List<Class<? extends Annotation>> productionStereotypes = List.of(
            Configuration.class, Component.class, Service.class, Repository.class);

        for (JavaClass testClass : testClasses) {
            for (Class<? extends Annotation> stereotype : productionStereotypes) {
                if (testClass.isAnnotatedWith(stereotype)) {
                    violations.add(testClass.getName() + " uses @" + stereotype.getSimpleName()
                        + " instead of @TestConfiguration/@TestComponent");
                }
            }
            // MVC endpoints may retain their mapping stereotype when also explicitly marked test-only.
            if ((testClass.isAnnotatedWith(Controller.class) || testClass.isAnnotatedWith(RestController.class))
                    && !testClass.isAnnotatedWith(TestComponent.class)) {
                violations.add(testClass.getName() + " is a test controller missing @TestComponent");
            }
            if (!testClass.isAnnotatedWith(TestConfiguration.class)
                    && testClass.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(Bean.class))) {
                violations.add(testClass.getName() + " declares @Bean methods outside @TestConfiguration");
            }
            if (testClass.isAnnotatedWith(ComponentScan.class)
                    && !testClass.getName().equals(InfrastructureTestComponents.class.getName())
                    && !testClass.getName().equals(OperationScopeTestComponents.class.getName())) {
                violations.add(testClass.getName() + " declares an independent @ComponentScan");
            }
        }
        assertEquals(List.of(), violations);
    }
}
