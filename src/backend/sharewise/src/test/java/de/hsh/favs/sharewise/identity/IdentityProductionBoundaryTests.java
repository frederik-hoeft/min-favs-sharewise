package de.hsh.favs.sharewise.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Missing trusted IAM wiring must not fall back to a fixture identity. */
@ComponentTest
@SpringJUnitConfig({OperationScopeTestComponents.class, IdentityProductionBoundaryTests.GroupFixture.class})
class IdentityProductionBoundaryTests {

    @Autowired OperationDispatcher dispatcher;

    @Test
    void unconfiguredIdentityFailsClosedWhenAnOperationRequestsIt() {
        BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> dispatcher.dispatch(GroupEntry.class, ignored -> null));
        assertTrue(hasCause(failure, NoSuchBeanDefinitionException.class),
                () -> "Expected missing IdentityContext bean, got " + failure);
    }

    @Test
    void identityFixtureClassesAreAbsentFromProductionClassFiles() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("de.hsh.favs.sharewise");
        assertFalse(production.contain(IdentityTestFixture.class.getName()));
        assertFalse(production.contain(IdentityTestConfiguration.class.getName()));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (expected.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    record GroupEntry(IdentityContext identity) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class GroupFixture {
        @Bean @Scope("operation")
        GroupEntry groupEntry(IdentityContext identity) {
            return new GroupEntry(identity);
        }
    }
}
