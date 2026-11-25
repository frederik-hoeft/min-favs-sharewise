package de.hsh.favs.sharewise.groups.internal.application;

import java.util.UUID;

/**
 * Group-owned registry of IAM user IDs that Group Management knows locally ({@code groups.users}).
 * Group Management never generates these IDs.
 */
public interface GroupUserRegistry {

    /** Whether the IAM user ID is registered locally. */
    boolean isKnown(UUID userId);

    /**
     * Registers an IAM user ID supplied by a trusted source; registering a known ID has no effect.
     * This is the single write path for test fixtures, event projection and authoritative lookup.
     */
    void provision(UUID userId);
}
