package de.hsh.favs.sharewise.shared.infra.persistence.jpa;

import jakarta.persistence.EntityManager;

/**
 * Provides JPA access to the active application-owned transaction segment.
 * The accessor is operation-scoped and can be reused across sequential segments.
 */
public interface JpaAccess {

    /**
     * Returns a guarded JPA facade for the current physical transaction segment.
     * No transaction is started by obtaining the facade.
     *
     * <p><strong>Do not capture or retain the returned {@link EntityManager}
     * beyond the lifetime of the current method call.</strong> Use it as a
     * method-local variable. The same restriction applies to derived queries,
     * streams, managed entity references and underlying persistence resources.
     * Queries are checked again when executed; the facade cannot be unwrapped
     * to obtain an unguarded provider-specific session. Detach or map values to
     * DTOs before returning them from an operation that may outlive its segment.
     * JPA query streams are not supported by the guarded facade; materialize
     * query results within the segment instead.
     *
     * @throws IllegalStateException if there is no current transaction segment
     */
    EntityManager entityManager();
}
