package de.hsh.favs.sharewise.tests.postgres;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Limits PostgreSQL contexts to context-owned production entities. The test classpath also
 * contains H2-only probe entities under shared infrastructure and tests, which are excluded.
 */
@TestConfiguration(proxyBeanMethods = false)
@EntityScan(basePackages = {
    "de.hsh.favs.sharewise.identity",
    "de.hsh.favs.sharewise.groups",
    "de.hsh.favs.sharewise.finance",
    "de.hsh.favs.sharewise.files",
    "de.hsh.favs.sharewise.notifications"
})
class ProductionEntityScanConfiguration { }
