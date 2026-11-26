package de.hsh.favs.sharewise.finance.internal.group;

import java.util.UUID;

/**
 * Shared group-local serialization boundary for Finance mutations. Every method requires
 * an active caller-owned transaction segment. Locks survive the method call and are
 * released by physical transaction completion; the returned snapshot carries no lock handle.
 *
 * <p>Failures propagate through Spring's repository exception translation: the adapter's
 * {@code IllegalArgumentException} or {@code IllegalStateException}, such as a rejected or failed
 * activation, reaches callers as {@code org.springframework.dao.InvalidDataAccessApiUsageException}
 * with the original as its cause, and SQL errors, including lock failures, as other
 * {@code DataAccessException} subtypes. Null arguments fail with an untranslated
 * {@code NullPointerException}.
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

    /**
     * Transition a locked provisioning root to open, leaving its revision and creation timestamp
     * unchanged, and return the resulting snapshot. The caller must hold the root's lock from this
     * store in the active segment, which the store does not verify; a non-provisioning snapshot is
     * rejected, and a stored root that is no longer provisioning fails rather than being reported
     * as an outcome.
     */
    FinancialGroup activate(FinancialGroup locked);
}
