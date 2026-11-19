package de.hsh.favs.sharewise.tests.postgres;

import de.hsh.favs.sharewise.testsupport.postgres.RollbackProbeEntityScanConfiguration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.p13crollback.RollbackProbeRow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** No PostgreSQL required: verify that the rollback probe augments production entity roots. */
class RollbackProbeEntityScanConfigurationTests {

    @Test
    void rollbackEntityScanIncludesTheProbeAndProductionPackages() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                RollbackProbeEntityScanConfiguration.class)) {
            java.util.List<String> packages = EntityScanPackages.get(context).getPackageNames();
            assertTrue(packages.contains(RollbackProbeRow.class.getPackageName()));
            assertTrue(packages.contains("de.hsh.favs.sharewise.groups"));
            assertTrue(packages.contains("de.hsh.favs.sharewise.identity"));
        }
    }
}
