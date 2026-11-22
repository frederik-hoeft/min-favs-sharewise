package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import java.time.Instant;
import java.util.UUID;

/**
 * A group as seen by one of its members.
 *
 * @param id the group ID
 * @param name the group name
 * @param description the description, or {@code null} if the group has none
 * @param createdAt the creation instant
 * @param version the metadata version that a replacement must name
 * @param memberCount the number of current members
 * @param callerRole the requesting member's role, {@code admin} or {@code member}
 */
public record GroupDetailsResponse(UUID id, String name, String description, Instant createdAt, long version,
        int memberCount, String callerRole) {

    static GroupDetailsResponse of(GroupDetails group) {
        return new GroupDetailsResponse(group.id(), group.metadata().name().value(),
            group.metadata().description().map(GroupDescription::value).orElse(null), group.createdAt(),
            group.version(), group.memberCount(), GroupRoles.toApi(group.callerRole()));
    }
}
