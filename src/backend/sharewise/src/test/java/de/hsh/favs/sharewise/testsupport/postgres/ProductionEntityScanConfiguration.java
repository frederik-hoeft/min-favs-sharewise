package de.hsh.favs.sharewise.testsupport.postgres;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Limits PostgreSQL contexts to context-owned production entities. Test-only JPA probe
 * entities are opted in by the tests that need their mappings.
 */
@TestConfiguration(proxyBeanMethods = false)
@EntityScan(basePackages = {
    "de.hsh.favs.sharewise.identity",
    "de.hsh.favs.sharewise.groups",
    "de.hsh.favs.sharewise.finance",
    "de.hsh.favs.sharewise.files",
    "de.hsh.favs.sharewise.notifications"
})
public class ProductionEntityScanConfiguration { }
