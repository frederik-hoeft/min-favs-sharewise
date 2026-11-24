package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.JooqCodegenProfile.Execution;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.OnError;
import org.junit.jupiter.api.Test;

/**
 * The effective per-execution configuration that {@link GeneratedJooqSchemasIT} regenerates
 * with. Synthetic POMs show that a setting changed only at plugin level reaches every
 * execution, so the freshness check follows configuration-only changes.
 */
class JooqCodegenProfileTests {

    private static final Path BASE = Path.of("/project");

    @Test
    void mergesPluginLevelSettingsIntoEveryExecution() throws Exception {
        List<Execution> executions = read(pom("""
            <onMetadataProblem>FAIL</onMetadataProblem>
            <jdbc><url>${jooq.codegen.jdbc.url}</url><password>${env.SECRET}</password></jdbc>
            <generator>
              <name>org.jooq.codegen.JavaGenerator</name>
              <database><name>org.jooq.meta.postgres.PostgresDatabase</name><includes>.*</includes></database>
              <generate><pojos>true</pojos><records>true</records></generate>
            </generator>""",
            execution("first", "<generate><pojos>false</pojos></generate>"),
            execution("second", "")));

        assertEquals(List.of("first", "second"), executions.stream().map(Execution::id).toList());
        for (Execution execution : executions) {
            Configuration configuration = execution.configuration();
            assertEquals(OnError.FAIL, configuration.getOnMetadataProblem());
            assertNull(configuration.getJdbc(), "the connection belongs to the caller");
            assertEquals("org.jooq.codegen.JavaGenerator", configuration.getGenerator().getName());
            assertEquals(".*", configuration.getGenerator().getDatabase().getIncludes());
            assertEquals(execution.id(), configuration.getGenerator().getDatabase().getInputSchema());
            assertEquals("/project/src/main/java", configuration.getGenerator().getTarget().getDirectory());
            assertTrue(configuration.getGenerator().getGenerate().isRecords());
        }
        assertFalse(executions.get(0).configuration().getGenerator().getGenerate().isPojos(), "the execution wins");
        assertTrue(executions.get(1).configuration().getGenerator().getGenerate().isPojos());
    }

    @Test
    void rejectsSettingsWhoseMavenSemanticsAreNotReproduced() {
        assertRejected("${excluded.tables}", pom(
            "<generator><database><excludes>${excluded.tables}</excludes></database></generator>",
            execution("first", "")));
        assertRejected("combine.*", pom(
            "<generator combine.self=\"override\"><name>org.jooq.codegen.JavaGenerator</name></generator>",
            execution("first", "")));
        assertRejected("<configurationFile>", pom(
            "<configurationFile>codegen.xml</configurationFile>", execution("first", "")));
    }

    @Test
    void everyRepositoryExecutionFailsOnMetadataProblems() throws Exception {
        List<Execution> executions = JooqCodegenProfile.executions(Path.of("pom.xml"));
        assertFalse(executions.isEmpty());
        for (Execution execution : executions) {
            // Without this, generating against an unmigrated database deletes the context's committed sources.
            assertEquals(OnError.FAIL, execution.configuration().getOnMetadataProblem(), execution::id);
        }
    }

    private static void assertRejected(String reason, String pom) {
        IllegalStateException rejection = assertThrows(IllegalStateException.class, () -> read(pom));
        assertTrue(rejection.getMessage().contains(reason), rejection::getMessage);
    }

    private static List<Execution> read(String pom) throws Exception {
        try (InputStream in = new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8))) {
            return JooqCodegenProfile.executions(in, BASE);
        }
    }

    private static String execution(String id, String generatorSettings) {
        return """
            <execution>
              <id>%1$s</id>
              <configuration>
                <generator>
                  %2$s
                  <database><inputSchema>%1$s</inputSchema></database>
                  <target><packageName>example.%1$s</packageName><directory>${project.basedir}/src/main/java</directory></target>
                </generator>
              </configuration>
            </execution>""".formatted(id, generatorSettings);
    }

    private static String pom(String pluginConfiguration, String... executions) {
        return """
            <project>
              <profiles>
                <profile>
                  <id>jooq-codegen</id>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jooq</groupId>
                        <artifactId>jooq-codegen-maven</artifactId>
                        <configuration>%s</configuration>
                        <executions>%s</executions>
                      </plugin>
                    </plugins>
                  </build>
                </profile>
              </profiles>
            </project>""".formatted(pluginConfiguration, String.join("", executions));
    }
}
