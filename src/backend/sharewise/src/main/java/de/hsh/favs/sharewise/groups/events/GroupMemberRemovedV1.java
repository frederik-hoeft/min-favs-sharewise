package de.hsh.favs.sharewise.groups.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The user is no longer a member of the group at the given membership revision.
 *
 * <p>Published for every committed leave or removal by an administrator. The type is the
 * resulting membership state; consumers order facts of one group by
 * {@code membershipRevision}, never by {@code occurredAt}. The event deliberately omits roles,
 * membership row IDs and profile data.
 *
 * @param eventId stable identifier of this fact, identical across redeliveries
 * @param groupId Group-issued group identifier
 * @param userId IAM-issued identifier of the user who left or was removed
 * @param membershipRevision the group's positive membership revision committed with this change
 * @param occurredAt change time; metadata only, not an ordering authority
 */
public record GroupMemberRemovedV1(UUID eventId, UUID groupId, UUID userId, long membershipRevision,
        Instant occurredAt) {

    public GroupMemberRemovedV1 {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (membershipRevision < 1) {
            throw new IllegalArgumentException("membershipRevision must be positive");
        }
    }
}
