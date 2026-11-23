package de.hsh.favs.sharewise.testsupport.postgres;

import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Bounds connections held by each disposable E2E Spring context.
 *
 * <p>Many E2E contexts may run concurrently against one PostgreSQL server. Hikari's default
 * ten-connection fixed-size pool would exhaust the server's connection limit even though each
 * fixture normally uses only one or two connections. Shared Integration contexts must instead
 * retain their normal pool capacity for concurrent rollback operations.
 */
public final class IsolatedPostgresPool {

    public static final int MAX_CONNECTIONS = 3;
    public static final int MIN_IDLE_CONNECTIONS = 0;

    private IsolatedPostgresPool() { }

    /** Applies the disposable fixture's connection budget to a Spring property map. */
    public static void configure(Map<String, Object> properties) {
        properties.put("spring.datasource.hikari.maximum-pool-size", MAX_CONNECTIONS);
        properties.put("spring.datasource.hikari.minimum-idle", MIN_IDLE_CONNECTIONS);
    }

    /** Applies the same budget to Spring TestContext-managed E2E fixtures. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> MAX_CONNECTIONS);
        registry.add("spring.datasource.hikari.minimum-idle", () -> MIN_IDLE_CONNECTIONS);
    }
}
