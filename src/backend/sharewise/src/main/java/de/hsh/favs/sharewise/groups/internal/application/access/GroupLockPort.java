package de.hsh.favs.sharewise.groups.internal.application.access;

import java.util.Optional;
import java.util.UUID;

/** Group-root locking capability shared by mutations of existing groups. */
public interface GroupLockPort {

    /** Locks the group row for update for a known local user; empty if either does not exist. */
    Optional<LockedGroup> lockGroup(UUID groupId, UUID userId);
}
