package de.hsh.favs.sharewise.finance.internal.actor;

import java.util.Optional;
import java.util.UUID;

/**
 * Retained Finance actors. Every method requires an active caller-owned transaction segment in
 * which the caller already holds the group's root lock from
 * {@link de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore}; that lock serializes all
 * actor writes of a group, so reads take no row lock of their own. Writes fail closed if the
 * protocol is violated, and unexpected persistence failures propagate to the transaction owner.
 */
public interface FinancialActorStore {

    /** The actor's current state, or empty if Finance has never observed this member of the group. */
    Optional<FinancialActor> find(UUID groupId, UUID userId);

    /** Inserts a previously unknown actor; an existing actor or missing root fails. */
    void insert(FinancialActor actor);

    /**
     * Replaces {@code current} by {@code next}, which must have the same identity and a strictly
     * greater revision. Fails unless the stored actor still equals {@code current}.
     */
    void supersede(FinancialActor current, FinancialActor next);
}
