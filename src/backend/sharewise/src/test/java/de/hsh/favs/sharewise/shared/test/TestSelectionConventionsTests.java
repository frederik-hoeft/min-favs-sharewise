package de.hsh.favs.sharewise.shared.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TestSelectionConventionsTests {

    @Test
    void testCategoryAnnotationsDeclareExpectedJUnitTags() {
        Tag componentTag = ComponentTest.class.getAnnotation(Tag.class);
        assertNotNull(componentTag);
        assertEquals("component", componentTag.value());

        Tag integrationTag = DatabaseIntegrationTest.class.getAnnotation(Tag.class);
        assertNotNull(integrationTag);
        assertEquals("integration", integrationTag.value());
    }
    
    @Test
    void compiledTestClassesAgreeWithTheirExecutionCategories() throws ClassNotFoundException {
        JavaClasses testClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.OnlyIncludeTests())
            .importPackages("de.hsh.favs.sharewise");
        for (JavaClass testClass : testClasses) {
            String name = testClass.getSimpleName();
            if (!name.endsWith("IT") && !name.endsWith("E2E") && !name.endsWith("Tests")) {
                continue;
            }
            Class<?> compiled = Class.forName(testClass.getName(), false,
                TestSelectionConventionsTests.class.getClassLoader());
            Tag tag = compiled.getDeclaredAnnotation(Tag.class);
            if (name.endsWith("IT")) {
                assertNotNull(tag, "Integration test requires explicit @Tag: " + name);
                assertEquals("integration", tag.value(), name);
            } else if (name.endsWith("E2E")) {
                assertNotNull(tag, "E2E test requires explicit @Tag: " + name);
                assertEquals("e2e", tag.value(), name);
            } else {
                assertTrue(tag == null || (!tag.value().equals("integration") && !tag.value().equals("e2e")),
                    "Database tests cannot use Unit-test filename: " + name);
                assertTrue(!compiled.isAnnotationPresent(DatabaseIntegrationTest.class),
                    "Database integration tests must use the *IT suffix: " + name);
            }
        }
    }
}
