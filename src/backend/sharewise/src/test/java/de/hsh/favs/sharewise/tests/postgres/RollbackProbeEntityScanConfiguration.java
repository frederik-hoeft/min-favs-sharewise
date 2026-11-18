package de.hsh.favs.sharewise.tests.postgres;

import de.hsh.favs.p13crollback.RollbackProbeRow;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Extends the Phase C production entity scan with only the opted-in PostgreSQL probe. */
@TestConfiguration(proxyBeanMethods = false)
@Import(ProductionEntityScanConfiguration.class)
@EntityScan(basePackageClasses = RollbackProbeRow.class)
class RollbackProbeEntityScanConfiguration { }
