package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import java.util.UUID;

/** Persists new groups. */
public interface GroupCreationPort {

    /**
     * Inserts a group and the creator's {@code admin} membership in the current segment.
     * The database rejects a creator who is not a locally known user. Creation does not advance
     * the membership revision: the new group's initial revision is the owner membership's revision.
     */
    CreatedGroup createWithAdministrator(UUID creatorId, GroupMetadata metadata);
}
