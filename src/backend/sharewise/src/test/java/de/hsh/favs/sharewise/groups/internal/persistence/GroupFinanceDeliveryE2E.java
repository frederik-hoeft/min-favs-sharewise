package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.ACTIVATION;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.LISTENERS;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.MEMBERSHIP_ADDED;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.MEMBERSHIP_REMOVED;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.awaitActor;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.awaitDelivered;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.awaitOpenRoot;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ALICE;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.BOB;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.change;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.create;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.invitation;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.membershipRevision;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ok;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.query;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.redeem;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.workspace;
import static de.hsh.favs.sharewise.testsupport.execution.Eventually.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.ActorsRecord;
import de.hsh.favs.sharewise.groups.events.GroupCreatedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberAddedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberRemovedV1;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.messaging.EventPublications;
import de.hsh.favs.sharewise.testsupport.messaging.EventPublications.StoredPublication;
import de.hsh.favs.sharewise.testsupport.postgres.MigrationScripts;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import de.hsh.favs.sharewise.testsupport.transactions.CommitFailures;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Group → Finance membership delivery through the real Group services, publication registry and
 * Finance listener on PostgreSQL (DLV-T06, DLV-T08 to DLV-T10, DLV-T14, DLV-T17). Finance state is observed on
 * independent connections; delivery is held by revoking a Finance privilege the listener needs.
 */
@Tag("e2e")
class GroupFinanceDeliveryE2E extends E2ETestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FINANCE_BACKFILL =
        "db/migration/platform/V202611261930__platform_bootstrap_finance_from_groups.sql";

    /** DLV-T09: creation opens the Finance root with the owner active at revision 1. */
    @Test
    void creationOpensTheFinanceRootWithTheOwnerActiveAtRevisionOne() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();

            awaitOpenRoot(fixture, group);
            awaitActor(fixture, group, ALICE, MembershipState.ACTIVE, 1);
            awaitDelivered(fixture);
            assertEquals(List.of(new FinancialActor(group, ALICE, MembershipState.ACTIVE, 1)),
                FinanceTestFixture.actors(fixture, group));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    /** DLV-T10: join, leave and rejoin keep one retained actor that follows each revision. */
    @Test
    void joinLeaveAndRejoinUpdateTheSameRetainedActorAtEachRevision() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, null);

            ok(redeem(fixture, BOB, link));
            awaitActor(fixture, group, BOB, MembershipState.ACTIVE, 2);
            ActorsRecord joined = FinanceTestFixture.actorRow(fixture, group, BOB);

            ok(change(fixture, BOB, group, workspace(fixture, BOB, group).callerMembershipId(),
                new MembershipChange.Remove()));
            awaitActor(fixture, group, BOB, MembershipState.REMOVED, 3);

            ok(redeem(fixture, BOB, link));
            awaitActor(fixture, group, BOB, MembershipState.ACTIVE, 4);
            awaitDelivered(fixture);

            assertEquals(joined.getCreatedAt(), FinanceTestFixture.actorRow(fixture, group, BOB).getCreatedAt(),
                "the rejoin reuses the retained actor row");
            assertEquals(List.of(new FinancialActor(group, ALICE, MembershipState.ACTIVE, 1),
                    new FinancialActor(group, BOB, MembershipState.ACTIVE, 4)),
                FinanceTestFixture.actors(fixture, group));
            awaitOpenRoot(fixture, group);
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    /**
     * DLV-T06 and DLV-T08: committed changes leave exactly their documented publications with
     * minimal payloads while Finance cannot apply them; restored privilege and resubmission
     * deliver all of them.
     */
    @Test
    void committedChangesLeaveMinimalRecoverablePublicationsWhileDeliveryIsHeld() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            asMigrator(fixture, "REVOKE INSERT ON finance.groups FROM sharewise_app");
            UUID group = ok(create(fixture, ALICE, "Secret trip")).id();
            await("both creation publications failed", () -> failed(fixture) == 2);
            assertEquals(Map.of(ACTIVATION, 1L, MEMBERSHIP_ADDED, 1L), listenerCounts(fixture));
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));

            CreatedInvitation link = invitation(fixture, group, null, null);
            ok(redeem(fixture, BOB, link));
            UUID bobMembership = workspace(fixture, BOB, group).callerMembershipId();
            ok(change(fixture, BOB, group, bobMembership, new MembershipChange.Remove()));
            await("all four publications failed", () -> failed(fixture) == 4);
            assertEquals(Map.of(ACTIVATION, 1L, MEMBERSHIP_ADDED, 2L, MEMBERSHIP_REMOVED, 1L), listenerCounts(fixture));
            assertEquals(List.of(), FinanceTestFixture.actors(fixture, group));

            for (StoredPublication publication : EventPublications.forListeners(fixture, LISTENERS)) {
                Map<String, Object> payload = payload(publication);
                String serialized = publication.serializedEvent();
                assertFalse(serialized.contains("Secret trip"), serialized);
                assertFalse(serialized.contains(bobMembership.toString()), serialized);
                assertFalse(serialized.toLowerCase().contains("admin"), serialized);
                assertEquals(group.toString(), payload.get("groupId"));
                switch (publication.listenerId()) {
                    case ACTIVATION -> {
                        assertEquals(GroupCreatedV1.class.getName(), publication.eventType());
                        assertEquals(Set.of("eventId", "groupId", "occurredAt"), payload.keySet());
                    }
                    case MEMBERSHIP_ADDED -> {
                        assertEquals(GroupMemberAddedV1.class.getName(), publication.eventType());
                        assertEquals(Set.of("eventId", "groupId", "userId", "membershipRevision", "occurredAt"),
                            payload.keySet());
                    }
                    case MEMBERSHIP_REMOVED -> {
                        assertEquals(GroupMemberRemovedV1.class.getName(), publication.eventType());
                        assertEquals(Set.of("eventId", "groupId", "userId", "membershipRevision", "occurredAt"),
                            payload.keySet());
                        assertEquals(BOB.toString(), payload.get("userId"));
                        assertEquals(3, ((Number) payload.get("membershipRevision")).intValue());
                    }
                    default -> throw new AssertionError("Unexpected listener " + publication.listenerId());
                }
            }

            asMigrator(fixture, "GRANT INSERT ON finance.groups TO sharewise_app");
            fixture.context().getBean(FailedEventPublications.class).resubmit(ResubmissionOptions.defaults());
            awaitDelivered(fixture);
            awaitOpenRoot(fixture, group);
            assertEquals(List.of(new FinancialActor(group, ALICE, MembershipState.ACTIVE, 1),
                    new FinancialActor(group, BOB, MembershipState.REMOVED, 3)),
                FinanceTestFixture.actors(fixture, group));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    /**
     * DLV-T14: a real database failure in Finance's own transaction leaves the join committed and
     * its publication failed; restored privilege and resubmission apply it.
     */
    @Test
    void consumerFailureLeavesTheJoinCommittedAndResubmissionAppliesIt() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            awaitDelivered(fixture);
            // The root and owner exist, so only the new actor's INSERT fails.
            asMigrator(fixture, "REVOKE INSERT ON finance.actors FROM sharewise_app");

            ok(redeem(fixture, BOB, invitation(fixture, group, null, null)));
            StoredPublication failed = EventPublications.awaitSingle(fixture, MEMBERSHIP_ADDED,
                "failed join publication", publication -> "FAILED".equals(publication.status()));
            assertTrue(failed.completionAttempts() >= 1, "the failed attempt is counted");
            assertEquals(2, membershipRevision(fixture, group), "the join committed independently of Finance");
            assertEquals(List.of(new FinancialActor(group, ALICE, MembershipState.ACTIVE, 1)),
                FinanceTestFixture.actors(fixture, group));

            asMigrator(fixture, "GRANT INSERT ON finance.actors TO sharewise_app");
            fixture.context().getBean(FailedEventPublications.class).resubmit(ResubmissionOptions.defaults());
            awaitActor(fixture, group, BOB, MembershipState.ACTIVE, 2);
            awaitDelivered(fixture);
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    /**
     * DLV-T17: a group that existed before the Finance listeners is initialized by the backfill
     * migration, and its next membership change is delivered and applied at the next revision.
     */
    @Test
    void membershipChangeAfterTheBackfillIsAppliedAtTheNextRevision() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = GroupTestFixture.seedGroup(fixture, "Pre-existing", ALICE);
            assertEquals(List.of(), EventPublications.forListeners(fixture, LISTENERS), "nothing to deliver");

            MigrationScripts.executeAsMigrator(fixture.database(), FINANCE_BACKFILL);
            assertEquals(Optional.of(new FinancialGroup(group, FinancialGroupState.OPEN, 0)),
                FinanceTestFixture.root(fixture, group));
            assertEquals(List.of(new FinancialActor(group, ALICE, MembershipState.ACTIVE, 1)),
                FinanceTestFixture.actors(fixture, group));

            ok(redeem(fixture, BOB, invitation(fixture, group, null, null)));
            awaitActor(fixture, group, BOB, MembershipState.ACTIVE, 2);
            awaitDelivered(fixture);
            assertEquals(List.of(new FinancialActor(group, ALICE, MembershipState.ACTIVE, 1),
                    new FinancialActor(group, BOB, MembershipState.ACTIVE, 2)),
                FinanceTestFixture.actors(fixture, group));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    /** DLV-T06: a commit failure after publication discards the group, its owner and both publications. */
    @Test
    void commitFailureAfterPublicationLeavesNoGroupOwnerOrPublication() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE);
            UUID committed = ok(create(fixture, ALICE, "Committed")).id();
            awaitDelivered(fixture);
            // Hold delivery, so a publication that escaped the rollback would remain observable.
            asMigrator(fixture, "REVOKE INSERT ON finance.groups FROM sharewise_app");

            IllegalStateException expected = CommitFailures.failNextCommit(fixture);
            assertSame(expected, assertThrows(IllegalStateException.class,
                () -> create(fixture, ALICE, "Rolled back")));

            assertEquals(List.of(committed.toString()), query(fixture, "SELECT id::text FROM groups.groups",
                row -> row.getString(1)));
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
            assertEquals(List.of(), EventPublications.forListeners(fixture, LISTENERS));
            assertEquals(List.of(committed.toString()), query(fixture, "SELECT id::text FROM finance.groups",
                row -> row.getString(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    private static long failed(IntegrationTestContext fixture) throws SQLException {
        return EventPublications.forListeners(fixture, LISTENERS).stream()
            .filter(publication -> "FAILED".equals(publication.status())).count();
    }

    private static Map<String, Long> listenerCounts(IntegrationTestContext fixture) throws SQLException {
        return EventPublications.forListeners(fixture, LISTENERS).stream()
            .collect(Collectors.groupingBy(StoredPublication::listenerId, Collectors.counting()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(StoredPublication publication) {
        return JSON.readValue(publication.serializedEvent(), Map.class);
    }

    private static void asMigrator(IntegrationTestContext fixture, String sql) throws SQLException {
        try (Connection migrator = fixture.database().connectAsMigrator();
             Statement statement = migrator.createStatement()) {
            statement.execute(sql);
        }
    }
}
