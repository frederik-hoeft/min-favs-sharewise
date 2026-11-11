package de.hsh.favs.sharewise.shared.infra.health;

/**
 * Pluggable readiness check evaluated by the system health endpoint.
 */
@FunctionalInterface
public interface ReadinessIndicator {

    /**
     * Returns {@code true} if the underlying dependency is ready to serve requests.
     */
    boolean isReady();
}
