package de.hsh.favs.sharewise.groups.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A group was created and committed by Group Management.
 *
 * <p>Published together with the {@link GroupMemberAddedV1} of the creator at membership
 * revision 1; their relative order carries no meaning. The event deliberately omits the group
 * name and description, which consumers do not need and the registry would otherwise retain.
 *
 * @param eventId stable identifier of this fact, identical across redeliveries
 * @param groupId Group-issued group identifier
 * @param occurredAt creation time; metadata only, not an ordering authority
 */
public record GroupCreatedV1(UUID eventId, UUID groupId, Instant occurredAt) {

    public GroupCreatedV1 {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
