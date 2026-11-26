package de.hsh.favs.sharewise.groups.internal.events;

import de.hsh.favs.sharewise.groups.events.GroupCreatedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberAddedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberRemovedV1;
import de.hsh.favs.sharewise.groups.internal.application.GroupEventPublisher;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes Group integration events through Spring. The Spring Modulith registry records one
 * publication per transactional listener in the caller's transaction; without listeners,
 * publication records nothing. Each fact receives a new event ID here, which the registry keeps
 * across redeliveries.
 */
@Component
class SpringGroupEventPublisher implements GroupEventPublisher {

    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    SpringGroupEventPublisher(ApplicationEventPublisher events) {
        this(events, Clock.systemUTC());
    }

    SpringGroupEventPublisher(ApplicationEventPublisher events, Clock clock) {
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void groupCreated(UUID groupId) {
        events.publishEvent(new GroupCreatedV1(UUID.randomUUID(), groupId, clock.instant()));
    }

    @Override
    public void memberAdded(UUID groupId, UUID userId, long membershipRevision) {
        events.publishEvent(new GroupMemberAddedV1(UUID.randomUUID(), groupId, userId, membershipRevision,
            clock.instant()));
    }

    @Override
    public void memberRemoved(UUID groupId, UUID userId, long membershipRevision) {
        events.publishEvent(new GroupMemberRemovedV1(UUID.randomUUID(), groupId, userId, membershipRevision,
            clock.instant()));
    }
}
