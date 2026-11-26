package de.hsh.favs.sharewise.finance.internal.events;

import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.MEMBERSHIP_ADDED;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.MEMBERSHIP_REMOVED;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.awaitActor;
import static de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture.awaitDelivered;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture.CommittedState;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.ActorsRecord;
import de.hsh.favs.sharewise.groups.events.GroupCreatedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberAddedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberRemovedV1;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.messaging.AsyncDeliveries;
import de.hsh.favs.sharewise.testsupport.messaging.EventPublications;
import de.hsh.favs.sharewise.testsupport.messaging.EventPublications.StoredPublication;
import de.hsh.favs.sharewise.testsupport.messaging.TestEventPublisher;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/**
 * Delivery guarantees of the Finance Group listener through the real publication registry,
 * executor and projection on PostgreSQL (DLV-T11 to DLV-T13, DLV-T15, DLV-T16). Facts are
 * published by test-only code in committed producer segments, so a test can choose orders,
 * revisions and redeliveries the Group services never produce. Each step awaits completion of
 * every outstanding publication before the next, so delivery order is publication order. Faults
 * are real database failures from privileges revoked in the test's own database.
 */
@Tag("e2e")
class GroupEventListenerE2E extends E2ETestBase {

    /** {@link #OWNER} sorts before {@link #MEMBER}, so expected actor lists are in user ID order. */
    private static final UUID OWNER = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be401");
    private static final UUID MEMBER = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be402");
    private static final String FAILED = "FAILED";

    /** DLV-T11 (a): creation, then the owner. */
    @Test
    void creationThenOwnerOpensTheRootWithTheOwnerAtRevisionOne() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();

            deliver(fixture, created(group));
            assertEquals(Optional.of(root(group, FinancialGroupState.OPEN)), FinanceTestFixture.root(fixture, group));
            assertEquals(List.of(), FinanceTestFixture.actors(fixture, group));

            deliver(fixture, added(group, OWNER, 1));
            assertConverged(fixture, group, active(group, OWNER, 1));
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class);
    }

    /** DLV-T11 (b): the owner, then creation. */
    @Test
    void ownerThenCreationConvergesOnTheSameState() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();

            deliver(fixture, added(group, OWNER, 1));
            assertEquals(Optional.of(root(group, FinancialGroupState.PROVISIONING)),
                FinanceTestFixture.root(fixture, group));
            assertEquals(List.of(active(group, OWNER, 1)), FinanceTestFixture.actors(fixture, group));

            deliver(fixture, created(group));
            assertConverged(fixture, group, active(group, OWNER, 1));
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class);
    }

    /** DLV-T11 (c): a later member before both creation facts. */
    @Test
    void laterMemberBeforeBothCreationFactsConverges() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();

            deliver(fixture, added(group, MEMBER, 2));
            assertEquals(Optional.of(root(group, FinancialGroupState.PROVISIONING)),
                FinanceTestFixture.root(fixture, group));

            deliver(fixture, created(group));
            deliver(fixture, added(group, OWNER, 1));
            assertConverged(fixture, group, active(group, OWNER, 1), active(group, MEMBER, 2));
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class);
    }

    /**
     * DLV-T12: an identical redelivery (same event ID), a stale revision and a repeated creation
     * abstain ({@code DUPLICATE}, {@code STALE}, {@code UNCHANGED}): every committed Finance row,
     * including {@code updated_at}, is unchanged, and each publication is completed.
     */
    @Test
    void redeliveredStaleAndRepeatedFactsChangeNothingAndComplete() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();
            GroupMemberAddedV1 owner = added(group, OWNER, 1);
            deliver(fixture, created(group), owner);
            deliver(fixture, added(group, MEMBER, 2));
            deliver(fixture, removed(group, MEMBER, 3));
            assertConverged(fixture, group, active(group, OWNER, 1),
                new FinancialActor(group, MEMBER, MembershipState.REMOVED, 3));
            CommittedState applied = FinanceTestFixture.committedState(fixture);

            for (Object abstaining : List.of(owner, added(group, MEMBER, 2), created(group))) {
                deliver(fixture, abstaining);
                assertEquals(applied, FinanceTestFixture.committedState(fixture), "unchanged after " + abstaining);
            }
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class);
    }

    /** DLV-T13: removal of a user Finance has never seen retains a tombstone and completes. */
    @Test
    void removalOfAnUnknownMemberInsertsATombstoneAndCompletes() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();
            deliver(fixture, created(group), added(group, OWNER, 1));

            deliver(fixture, removed(group, MEMBER, 2));
            assertConverged(fixture, group, active(group, OWNER, 1),
                new FinancialActor(group, MEMBER, MembershipState.REMOVED, 2));
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class);
    }

    /**
     * DLV-T15: a same-revision contradiction is never acknowledged. Its publication stays failed
     * across resubmissions and the actor keeps the first fact.
     */
    @Test
    void contradictionAtTheSameRevisionStaysIncompleteAcrossResubmissions() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();
            deliver(fixture, added(group, MEMBER, 5));
            CommittedState applied = FinanceTestFixture.committedState(fixture);

            TestEventPublisher.publishCommitted(fixture, removed(group, MEMBER, 5));
            int attempts = awaitFailedRemoval(fixture, 0).completionAttempts();
            FailedEventPublications failed = fixture.context().getBean(FailedEventPublications.class);
            for (int resubmission = 1; resubmission <= 2; resubmission++) {
                failed.resubmit(ResubmissionOptions.defaults());
                awaitFailedRemoval(fixture, attempts + resubmission);
            }

            assertEquals(applied, FinanceTestFixture.committedState(fixture), "the contradicting fact wrote nothing");
            assertEquals(List.of(active(group, MEMBER, 5)), FinanceTestFixture.actors(fixture, group));
            assertEquals(0, EventPublications.count(fixture, MEMBERSHIP_ADDED), "the first fact completed");
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class);
    }

    /**
     * DLV-T16: when completion fails after the Finance commit, the publication is retained, the
     * staleness monitor marks it failed, and the resubmitted redelivery abstains and completes.
     * Finance applies the fact exactly once.
     */
    @Test
    void lostCompletionIsRedeliveredWithoutReapplyingTheFact() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();
            deliver(fixture, created(group), added(group, OWNER, 1));
            // The registry deletes completed publications; without DELETE, completion fails in its own
            // transaction after the listener returned.
            asMigrator(fixture, "REVOKE DELETE ON messaging.event_publication FROM sharewise_app");

            TestEventPublisher.publishCommitted(fixture, added(group, MEMBER, 2));
            awaitActor(fixture, group, MEMBER, MembershipState.ACTIVE, 2);
            CommittedState applied = FinanceTestFixture.committedState(fixture);
            // The delivery task, including its completion attempt, has ended; the publication remains.
            AsyncDeliveries.awaitIdle(fixture);
            assertEquals(1, EventPublications.count(fixture, MEMBERSHIP_ADDED), "completion was lost");
            EventPublications.awaitSingle(fixture, MEMBERSHIP_ADDED, "stale publication of the lost completion",
                publication -> FAILED.equals(publication.status()));

            asMigrator(fixture, "GRANT DELETE ON messaging.event_publication TO sharewise_app");
            fixture.context().getBean(FailedEventPublications.class).resubmit(ResubmissionOptions.defaults());
            awaitDelivered(fixture);

            assertEquals(applied, FinanceTestFixture.committedState(fixture), "the redelivery abstained");
            ActorsRecord member = FinanceTestFixture.actorRow(fixture, group, MEMBER);
            assertEquals(member.getCreatedAt(), member.getUpdatedAt(), "the actor was written exactly once");
        }, ProductionEntityScanConfiguration.class, TestEventPublisher.Configuration.class, FastStaleness.class);
    }

    /** Publishes the facts in one committed segment and waits until all publications completed. */
    private static void deliver(IntegrationTestContext fixture, Object... events) {
        TestEventPublisher.publishCommitted(fixture, events);
        awaitDelivered(fixture);
    }

    /**
     * The group has an open root at financial revision zero and exactly these actors, given in
     * user ID order, and no outstanding publication.
     */
    private static void assertConverged(IntegrationTestContext fixture, UUID group, FinancialActor... actors)
            throws SQLException {
        assertEquals(Optional.of(root(group, FinancialGroupState.OPEN)), FinanceTestFixture.root(fixture, group));
        assertEquals(List.of(actors), FinanceTestFixture.actors(fixture, group));
        assertEquals(List.of(), EventPublications.forListeners(fixture, GroupDeliveryFixture.LISTENERS));
    }

    private static StoredPublication awaitFailedRemoval(IntegrationTestContext fixture, int minimumAttempts) {
        StoredPublication publication = EventPublications.awaitSingle(fixture, MEMBERSHIP_REMOVED,
            "failed removal after at least " + minimumAttempts + " attempt(s)",
            candidate -> FAILED.equals(candidate.status()) && candidate.completionAttempts() >= minimumAttempts);
        assertEquals(GroupMemberRemovedV1.class.getName(), publication.eventType());
        return publication;
    }

    private static FinancialGroup root(UUID group, FinancialGroupState state) {
        return new FinancialGroup(group, state, 0);
    }

    private static FinancialActor active(UUID group, UUID user, long revision) {
        return new FinancialActor(group, user, MembershipState.ACTIVE, revision);
    }

    private static GroupCreatedV1 created(UUID group) {
        return new GroupCreatedV1(UUID.randomUUID(), group, Instant.now());
    }

    private static GroupMemberAddedV1 added(UUID group, UUID user, long revision) {
        return new GroupMemberAddedV1(UUID.randomUUID(), group, user, revision, Instant.now());
    }

    private static GroupMemberRemovedV1 removed(UUID group, UUID user, long revision) {
        return new GroupMemberRemovedV1(UUID.randomUUID(), group, user, revision, Instant.now());
    }

    private static void asMigrator(IntegrationTestContext fixture, String sql) throws SQLException {
        try (Connection migrator = fixture.database().connectAsMigrator();
             Statement statement = migrator.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Moves a publication whose completion was lost from {@code PROCESSING} to {@code FAILED}
     * within seconds instead of the production ten minutes. Resubmission stays manual: the
     * scheduled resubmitter keeps its production interval.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FastStaleness {
        @Bean
        static BeanFactoryPostProcessor fastProcessingStaleness(ConfigurableEnvironment environment) {
            return beanFactory -> environment.getPropertySources().addFirst(new MapPropertySource(
                "fast-processing-staleness", Map.of(
                    "spring.modulith.events.staleness.check-interval", "PT1S",
                    "spring.modulith.events.staleness.processing", "PT2S")));
        }
    }
}
