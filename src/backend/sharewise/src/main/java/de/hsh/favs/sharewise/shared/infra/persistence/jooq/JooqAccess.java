package de.hsh.favs.sharewise.shared.infra.persistence.jooq;

import org.jooq.DSLContext;

/**
 * Provides jOOQ access to the active application-owned transaction segment.
 * This is an infrastructure accessor, not a business-facing persistence port.
 * The accessor itself is operation-scoped and can be used across sequential segments.
 */
public interface JooqAccess {

    /**
     * Returns a jOOQ facade bound to the currently active transaction segment.
     * Fails when no application transaction segment is active. The returned
     * facade and queries created from it are NOT reusable in later segments.
     *
     * <p><strong>Do not capture or retain the returned {@link DSLContext}
     * beyond the lifetime of the current method call.</strong> Use it as a
     * method-local variable, execute SQL synchronously, and return materialized
     * results. The same restriction applies to queries, configurations, cursors,
     * streams and raw JDBC resources derived from it. Never derive a jOOQ
     * configuration that removes the segment checks. jOOQ's own
     * {@code transaction(...)} and {@code transactionResult(...)} entry points
     * are disabled: only {@link de.hsh.favs.sharewise.shared.infra.transactions.Transactions#execute}
     * owns transaction boundaries. The accessor can safely
     * be retained by an operation-scoped repository; the returned facade cannot.
     *
     * @throws IllegalStateException if there is no current transaction segment
     */
    DSLContext dsl();
}
