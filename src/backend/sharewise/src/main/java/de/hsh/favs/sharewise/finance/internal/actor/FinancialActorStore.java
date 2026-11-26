package de.hsh.favs.sharewise.finance.internal.actor;

import java.util.Optional;
import java.util.UUID;

/**
 * Retained Finance actors. Every method requires an active caller-owned transaction segment in
 * which the caller already holds the group's root lock from
 * {@link de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore}; that lock serializes all
 * actor reads and writes of a group, so reads take no row lock of their own.
 *
 * <p>Holding the root lock is a caller-enforced precondition: the store neither acquires nor
 * verifies it. The database guards of {@link #insert} and {@link #supersede} detect a duplicate or
 * orphaned actor and an actor changed since it was read, but a write without the root lock succeeds
 * whenever no such conflict exists.
 *
 * <p>Failures propagate to the transaction owner through Spring's repository exception
 * translation. A rejected argument or a failed compare-and-set reaches callers as
 * {@code org.springframework.dao.InvalidDataAccessApiUsageException} whose cause is the adapter's
 * {@code IllegalArgumentException} or {@code IllegalStateException}; SQL errors such as key
 * violations arrive as other {@code DataAccessException} subtypes. Null arguments fail with an
 * untranslated {@code NullPointerException}. No failure is reported as a successful result.
 */
public interface FinancialActorStore {

    /** The actor's current state, or empty if Finance has never observed this member of the group. */
    Optional<FinancialActor> find(UUID groupId, UUID userId);

    /** Inserts a previously unknown actor; the keys reject an existing actor or a missing root. */
    void insert(FinancialActor actor);

    /**
     * Replaces {@code current} by {@code next}, which must have the same identity and a strictly
     * greater revision; another identity or revision is rejected before any SQL. Fails unless the
     * stored actor still equals {@code current}, with the adapter's {@code IllegalStateException}
     * as the cause of the translated exception.
     */
    void supersede(FinancialActor current, FinancialActor next);
}
