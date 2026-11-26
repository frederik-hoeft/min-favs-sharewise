package de.hsh.favs.sharewise.groups.internal.application;

import java.util.UUID;

/**
 * Publishes Group integration facts in the caller's transaction segment.
 *
 * <p>Services call it only on the path that returns a successful mutation, after the last write
 * of that path, so the facts commit or roll back with the Group state they describe. The
 * revision passed for a membership fact must be the one returned by the membership write that
 * produced it. Each call publishes one new fact with its own event ID.
 */
public interface GroupEventPublisher {

    void groupCreated(UUID groupId);

    void memberAdded(UUID groupId, UUID userId, long membershipRevision);

    void memberRemoved(UUID groupId, UUID userId, long membershipRevision);
}
