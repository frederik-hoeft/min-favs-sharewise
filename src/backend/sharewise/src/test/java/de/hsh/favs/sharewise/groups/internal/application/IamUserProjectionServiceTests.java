package de.hsh.favs.sharewise.groups.internal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.projection.IamUserProjectionService;
import de.hsh.favs.sharewise.identity.events.UserRegisteredV1;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IamUserProjectionServiceTests {

    private static final UUID USER = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be3a1");

    private final InMemoryGroups groups = new InMemoryGroups();
    private final RecordingTransactions.Log log = new RecordingTransactions.Log();
    private final IamUserProjectionService projection =
        new IamUserProjectionService(groups, new RecordingTransactions(log));

    @Test
    void registrationProvisionsTheUserInOneCommittedSegmentWithoutCallerAdmission() {
        projection.project(registered(USER));

        assertTrue(groups.isKnown(USER));
        assertEquals(List.of(TransactionDecision.COMMIT), log.decisions());
    }

    @Test
    void redeliveryIsHarmless() {
        projection.project(registered(USER));
        projection.project(registered(USER));

        assertTrue(groups.isKnown(USER));
        assertEquals(List.of(TransactionDecision.COMMIT, TransactionDecision.COMMIT), log.decisions());
        assertEquals(0, groups.writes(), "provisioning users does not count as a group write");
    }

    private static UserRegisteredV1 registered(UUID userId) {
        return new UserRegisteredV1(UUID.randomUUID(), userId, Instant.parse("2026-11-24T12:00:00Z"));
    }
}
