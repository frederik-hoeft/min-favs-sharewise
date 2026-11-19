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
    void ciRequiresSuccessfulBuildsBeforeTestsAndOrdersPostgresGates() throws IOException {
        String pipeline = Files.readString(Path.of("../../../.gitlab-ci.yml"));
        String stageBlock = pipeline.split("stages:\n", 2)[1].split("\n\n", 2)[0];
        List<String> stages = stageBlock.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("- "))
            .map(line -> line.substring(2).split("\\s+#", 2)[0])
            .toList();
        assertEquals(List.of("build", "test", "test-backend-integration", "test-backend-e2e"), stages);
        assertTrue(!pipeline.contains("needs:"), "Project jobs must not bypass stage barriers");
        assertTrue(pipeline.contains("Security/SAST.gitlab-ci.yml"));
        assertTrue(pipeline.contains("Security/Secret-Detection.gitlab-ci.yml"));
        assertTrue(pipeline.contains("AST_ENABLE_MR_PIPELINES: 'true'"));

        for (String job : new String[]{"build-backend", "build-frontend"}) {
            assertTrue(ciJob(pipeline, job).contains("  stage: build"), job);
        }
        for (String job : new String[]{"test-backend-unit", "test-frontend-lint",
                "test-frontend-typecheck", "test-frontend-test-typecheck", "test-frontend-vitest",
                "test-frontend-static-export", "test-offline-handoff", "sast", "secret_detection"}) {
            assertTrue(ciJob(pipeline, job).contains("  stage: test"), job);
        }
        assertTrue(ciJob(pipeline, "test-backend-integration").contains("  stage: test-backend-integration"));
        assertTrue(ciJob(pipeline, "test-backend-e2e").contains("  stage: test-backend-e2e"));
        assertTrue(ciJob(pipeline, "test-backend-integration").contains("-Pintegration-tests clean verify"));
        assertTrue(ciJob(pipeline, "test-backend-e2e").contains("-Pe2e-tests clean verify"));
        assertTrue(ciJob(pipeline, "test-backend-unit").contains("-Punit-tests clean verify"));

        // Independent jobs must not fetch package artifacts from the earlier build stage.
        assertTrue(ciJob(pipeline, ".backend-maven").contains("  dependencies: []"));
        assertTrue(ciJob(pipeline, ".frontend-npm").contains("  dependencies: []"));
        assertTrue(ciJob(pipeline, "test-offline-handoff").contains("  dependencies: []"));
        assertTrue(ciJob(pipeline, "secret_detection").contains("  dependencies: []"));
        assertTrue(ciJob(pipeline, "test-frontend-lint").contains("npm run lint"));
        assertTrue(ciJob(pipeline, "test-frontend-typecheck").contains("npm run typecheck"));
        assertTrue(ciJob(pipeline, "test-frontend-test-typecheck").contains("npm run test:typecheck"));
        assertTrue(ciJob(pipeline, "test-frontend-vitest").contains("--reporter=junit"));
        assertTrue(ciJob(pipeline, "test-frontend-vitest").contains("src/frontend/vitest-junit.xml"));
        assertTrue(ciJob(pipeline, "build-frontend").contains("npm run build"));
        assertTrue(ciJob(pipeline, "test-frontend-static-export").contains("npm run test:static-export"));
        assertTrue(ciJob(pipeline, "build-frontend").contains("policy: pull-push"));
        assertTrue(pipeline.contains("src/frontend/.npm/"));
        assertTrue(!pipeline.contains("src/frontend/node_modules/"));
    }

    private static String ciJob(String pipeline, String name) {
        String marker = "\n" + name + ":\n";
        int start = pipeline.indexOf(marker);
        assertTrue(start >= 0, "Missing CI job: " + name);
        int end = pipeline.indexOf("\n\n", start + marker.length());
        return pipeline.substring(start, end < 0 ? pipeline.length() : end);
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
