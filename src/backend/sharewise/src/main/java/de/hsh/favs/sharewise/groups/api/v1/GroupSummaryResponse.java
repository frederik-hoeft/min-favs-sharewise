package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.group.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import java.util.UUID;

/**
 * A list entry for one group of the requesting member.
 *
 * @param id the group ID
 * @param name the group name
 * @param description the description, or {@code null} if the group has none
 * @param memberCount the number of current members
 * @param callerRole the requesting member's role, {@code admin} or {@code member}
 */
public record GroupSummaryResponse(UUID id, String name, String description, int memberCount, String callerRole) {

    static GroupSummaryResponse of(GroupSummary group) {
        return new GroupSummaryResponse(group.id(), group.metadata().name().value(),
            group.metadata().description().map(GroupDescription::value).orElse(null), group.memberCount(),
            GroupRoles.toApi(group.callerRole()));
    }
}
