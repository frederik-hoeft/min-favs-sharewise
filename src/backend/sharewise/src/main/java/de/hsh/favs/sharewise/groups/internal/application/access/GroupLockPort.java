package de.hsh.favs.sharewise.groups.internal.application.access;

import java.util.Optional;
import java.util.UUID;

/** Group-root locking capability for member-scoped mutations of existing groups. */
public interface GroupLockPort {

    /**
     * Locks the root only if the caller has membership in the lock-selection snapshot.
     * Empty for missing groups or memberships. A separate post-lock read must establish live
     * membership and role before any mutation; the selection predicate is not authorization.
     */
    Optional<LockedGroup> lockGroupForMember(UUID groupId, UUID userId);
}
