package de.hsh.favs.sharewise.groups.internal.application.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Member-scoped group projections. */
public interface GroupReadPort {

    /** Groups ordered by case-insensitive name then ID; absent only for an unknown local user. */
    Optional<List<GroupSummary>> listForMember(UUID userId);

    /** The group if it exists and the user is a member; otherwise empty. */
    Optional<GroupDetails> findForMember(UUID groupId, UUID userId);

    /** The metadata and roster from a single member-scoped database statement. */
    Optional<GroupWorkspace> findWorkspace(UUID groupId, UUID userId);
}
