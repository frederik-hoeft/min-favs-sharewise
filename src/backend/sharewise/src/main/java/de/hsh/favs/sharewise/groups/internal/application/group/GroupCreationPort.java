package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import java.util.UUID;

/** Persists new groups. */
public interface GroupCreationPort {

    /**
     * Inserts a group and the creator's {@code admin} membership in the current segment.
     * The database rejects a creator who is not a locally known user.
     */
    GroupDetails createWithAdministrator(UUID creatorId, GroupMetadata metadata);
}
