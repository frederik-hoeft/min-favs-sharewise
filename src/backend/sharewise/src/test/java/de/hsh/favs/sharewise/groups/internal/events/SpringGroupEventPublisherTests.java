package de.hsh.favs.sharewise.groups.internal.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.hsh.favs.sharewise.groups.events.GroupCreatedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberAddedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberRemovedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The adapter maps each port call to exactly one event with a fresh event ID and the clock time. */
class SpringGroupEventPublisherTests {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void publishesOneVersionedEventPerFact() {
        List<Object> published = new ArrayList<>();
        SpringGroupEventPublisher publisher = new SpringGroupEventPublisher(published::add,
            Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.groupCreated(GROUP);
        publisher.memberAdded(GROUP, USER, 1);
        publisher.memberRemoved(GROUP, USER, 2);

        assertEquals(3, published.size());
        GroupCreatedV1 created = (GroupCreatedV1) published.get(0);
        GroupMemberAddedV1 added = (GroupMemberAddedV1) published.get(1);
        GroupMemberRemovedV1 removed = (GroupMemberRemovedV1) published.get(2);
        assertEquals(new GroupCreatedV1(created.eventId(), GROUP, NOW), created);
        assertEquals(new GroupMemberAddedV1(added.eventId(), GROUP, USER, 1, NOW), added);
        assertEquals(new GroupMemberRemovedV1(removed.eventId(), GROUP, USER, 2, NOW), removed);
        assertNotEquals(created.eventId(), added.eventId());
        assertNotEquals(added.eventId(), removed.eventId());
    }
}
