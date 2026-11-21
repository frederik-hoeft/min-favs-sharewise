package de.hsh.favs.sharewise.groups.internal.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jooq.codegen.GenerationTool;
import org.jooq.codegen.JavaGenerator;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Target;
import org.jooq.meta.postgres.PostgresDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GM-12 / GM-T15: regenerates the {@code groups} jOOQ sources from the suite's freshly
 * Flyway-migrated database and requires them to equal the committed sources byte for byte.
 *
 * <p>The generator settings mirror the {@code groups} execution of the {@code jooq-codegen}
 * Maven profile. If the two diverge, regenerated committed sources no longer match and this
 * test fails, so the profile cannot silently drift from the check.
 */
@Tag("integration")
class GeneratedGroupsSchemaIT extends IntegrationTestBase {

    private static final String PACKAGE = "de.hsh.favs.sharewise.groups.internal.persistence.generated";
    private static final Path COMMITTED_SOURCES = Path.of("src", "main", "java");

    @Test
    void committedGeneratedSourcesMatchTheMigratedSchema(@TempDir Path output) throws Exception {
        try (Connection migrator = fixture().database().connectAsMigrator()) {
            GenerationTool generator = new GenerationTool();
            generator.setConnection(migrator);
            generator.run(new Configuration().withGenerator(new Generator()
                .withName(JavaGenerator.class.getName())
                .withDatabase(new Database()
                    .withName(PostgresDatabase.class.getName())
                    .withInputSchema("groups")
                    .withIncludes(".*"))
                .withGenerate(new Generate().withDaos(false).withPojos(false).withRecords(true))
                .withTarget(new Target().withPackageName(PACKAGE).withDirectory(output.toString()))));
        }

        Map<String, String> regenerated = sources(output);
        Map<String, String> committed = sources(COMMITTED_SOURCES);
        assertFalse(regenerated.isEmpty(), "jOOQ generated no sources for the groups schema");
        assertEquals(regenerated.keySet(), committed.keySet(), "generated file set differs; rerun -Pjooq-codegen");
        for (Map.Entry<String, String> file : regenerated.entrySet()) {
            assertEquals(file.getValue(), committed.get(file.getKey()),
                () -> file.getKey() + " differs from the migrated schema; rerun -Pjooq-codegen");
        }
    }

    private static Map<String, String> sources(Path root) throws IOException {
        Path packageDirectory = root.resolve(PACKAGE.replace('.', '/'));
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(packageDirectory)) {
            return files;
        }
        try (Stream<Path> paths = Files.walk(packageDirectory)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                files.put(packageDirectory.relativize(file).toString(), Files.readString(file));
            }
        }
        return files;
    }
}
