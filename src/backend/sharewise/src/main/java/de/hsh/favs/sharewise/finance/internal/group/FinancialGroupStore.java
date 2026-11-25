package de.hsh.favs.sharewise.finance.internal.group;

import java.util.UUID;

/**
 * Shared group-local serialization boundary for Finance mutations. Both methods require
 * an active caller-owned transaction segment. Locks survive the method call and are
 * released by physical transaction completion; the returned snapshot carries no lock handle.
 */
public interface FinancialGroupStore {

    /**
     * Ensure a root exists (creating it as provisioning if absent) and lock it without changing an existing root.
     * Reserved for operations processing trusted Group-originated facts; the caller establishes trust.
     * Neither activation nor actor creation nor revision advancement is implied.
     * Unexpected persistence failures propagate to the transaction owner.
     */
    FinancialGroup ensureAndLock(UUID groupId);

    /**
     * Lock an existing root without creating one. Absence means no Finance row was found
     * or locked, not that the upstream Group does not exist. The result provides the shared
     * readiness check; the caller owns authorization and operation-specific lifecycle policy.
     */
    FinancialGroupLookup lockExisting(UUID groupId);
}
