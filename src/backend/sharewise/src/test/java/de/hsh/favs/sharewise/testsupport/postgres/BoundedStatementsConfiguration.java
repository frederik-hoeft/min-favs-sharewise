package de.hsh.favs.sharewise.testsupport.postgres;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.PropertySource;

/**
 * Bounds every statement of the context's pooled connections, so real service-owned transactions
 * blocked in a concurrency E2E fail instead of hanging, without wrapping those transactions.
 */
@TestConfiguration(proxyBeanMethods = false)
@PropertySource("classpath:bounded-statements-test.properties")
public class BoundedStatementsConfiguration { }
