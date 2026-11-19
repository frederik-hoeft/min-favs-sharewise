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
    void mavenAndCiExposeThreeIndependentlySelectedTestSuites() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String pipeline = Files.readString(Path.of("../../../.gitlab-ci.yml"));
        assertTrue(pom.contains("<id>unit-tests</id>"));
        assertTrue(pom.contains("<id>integration-tests</id>"));
        assertTrue(pom.contains("<id>e2e-tests</id>"));
        assertTrue(pom.contains("<include>**/*Tests.java</include>"));
        assertTrue(pom.contains("<include>**/*Test.java</include>"));
        assertTrue(pom.contains("<databaseTestIncludes>**/*IT.java</databaseTestIncludes>"));
        assertTrue(pom.contains("<databaseTestIncludes>**/*E2E.java</databaseTestIncludes>"));
        assertTrue(pom.contains("<skipDatabaseTests>true</skipDatabaseTests>"));
        assertTrue(pipeline.contains("test-backend-unit:"));
        assertTrue(pipeline.contains("test-backend-integration:"));
        assertTrue(pipeline.contains("test-backend-e2e:"));
        assertTrue(pipeline.contains("postgres:18-trixie"));
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

    @Test
    void offlineTestClasspathHasNoH2DatabaseDependency() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(!pom.contains("<artifactId>h2</artifactId>"));
        assertTrue(!Files.exists(Path.of("src/test/resources/config/application.properties")));
    }
}
