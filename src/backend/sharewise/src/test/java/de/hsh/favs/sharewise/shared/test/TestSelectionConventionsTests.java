package de.hsh.favs.sharewise.shared.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void defaultMavenTestSelectionExcludesIntegrationTagAndDefinesIntegrationProfile() throws IOException {
        String pomContent = Files.readString(Path.of("pom.xml"));
        assertTrue(
                pomContent.contains("<excludedGroups>integration</excludedGroups>"),
                "pom.xml must exclude the 'integration' JUnit tag by default");
        assertTrue(
                pomContent.contains("<id>integration-tests</id>"),
                "pom.xml must define the 'integration-tests' Maven profile");
        assertTrue(
                pomContent.contains("<groups>integration</groups>"),
                "integration-tests profile must select the 'integration' JUnit tag");

        String configuredExcludedGroups = System.getProperty("sharewise.surefire.excludedGroups");
        if (configuredExcludedGroups != null) {
            assertEquals("integration", configuredExcludedGroups);
        }
    }

    @DatabaseIntegrationTest
    @Test
    void integrationTaggedTestRunsOnlyWhenIntegrationGroupIsExplicitlySelected() {
        String selectedGroups = System.getProperty("sharewise.surefire.groups", "");
        assertTrue(
                selectedGroups.contains("integration"),
                "@DatabaseIntegrationTest must be excluded from the default test run and execute only when 'integration' group is selected");
    }
}
