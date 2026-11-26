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
 * whenever no such conflict exists. Unexpected persistence failures propagate to the transaction owner.
 */
public interface FinancialActorStore {

    /** The actor's current state, or empty if Finance has never observed this member of the group. */
    Optional<FinancialActor> find(UUID groupId, UUID userId);

    /** Inserts a previously unknown actor; the keys reject an existing actor or a missing root. */
    void insert(FinancialActor actor);

    /**
     * Replaces {@code current} by {@code next}, which must have the same identity and a strictly
     * greater revision. Fails with an {@link IllegalStateException} unless the stored actor still
     * equals {@code current}.
     */
    void supersede(FinancialActor current, FinancialActor next);
}
