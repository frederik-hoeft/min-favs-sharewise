package de.hsh.favs.sharewise.groups.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** DLV-T01: the persisted Group event contracts reject incomplete facts and nonpositive revisions. */
class GroupEventContractTests {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-26T12:00:00Z");

    @Test
    void creationRejectsMissingFields() {
        assertThrows(NullPointerException.class, () -> new GroupCreatedV1(null, GROUP, AT));
        assertThrows(NullPointerException.class, () -> new GroupCreatedV1(EVENT, null, AT));
        assertThrows(NullPointerException.class, () -> new GroupCreatedV1(EVENT, GROUP, null));
        assertEquals(GROUP, new GroupCreatedV1(EVENT, GROUP, AT).groupId());
    }

    @Test
    void membershipFactsRejectMissingFields() {
        assertThrows(NullPointerException.class, () -> new GroupMemberAddedV1(null, GROUP, USER, 1, AT));
        assertThrows(NullPointerException.class, () -> new GroupMemberAddedV1(EVENT, null, USER, 1, AT));
        assertThrows(NullPointerException.class, () -> new GroupMemberAddedV1(EVENT, GROUP, null, 1, AT));
        assertThrows(NullPointerException.class, () -> new GroupMemberAddedV1(EVENT, GROUP, USER, 1, null));
        assertThrows(NullPointerException.class, () -> new GroupMemberRemovedV1(null, GROUP, USER, 1, AT));
        assertThrows(NullPointerException.class, () -> new GroupMemberRemovedV1(EVENT, null, USER, 1, AT));
        assertThrows(NullPointerException.class, () -> new GroupMemberRemovedV1(EVENT, GROUP, null, 1, AT));
        assertThrows(NullPointerException.class, () -> new GroupMemberRemovedV1(EVENT, GROUP, USER, 1, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void membershipFactsRejectNonpositiveRevisions(long revision) {
        assertThrows(IllegalArgumentException.class, () -> new GroupMemberAddedV1(EVENT, GROUP, USER, revision, AT));
        assertThrows(IllegalArgumentException.class, () -> new GroupMemberRemovedV1(EVENT, GROUP, USER, revision, AT));
    }

    @Test
    void membershipFactsAcceptTheFullPositiveRange() {
        for (long revision : List.of(1L, Long.MAX_VALUE)) {
            assertEquals(revision, new GroupMemberAddedV1(EVENT, GROUP, USER, revision, AT).membershipRevision());
            assertEquals(revision, new GroupMemberRemovedV1(EVENT, GROUP, USER, revision, AT).membershipRevision());
        }
    }
}
