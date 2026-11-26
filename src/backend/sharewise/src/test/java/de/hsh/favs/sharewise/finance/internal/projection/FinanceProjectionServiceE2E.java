package de.hsh.favs.sharewise.finance.internal.projection;

import static de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture.SEEDED_AT;
import static de.hsh.favs.sharewise.finance.internal.projection.ProjectionDispatch.activate;
import static de.hsh.favs.sharewise.finance.internal.projection.ProjectionDispatch.apply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.FinancialActorStore;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture.CommittedState;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.ActorsRecord;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.GroupsRecord;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/**
 * Projection operations dispatched as production operations with real commits, observed from an
 * independent application-role connection: activation, membership ordering, contradiction,
 * failure atomicity and isolation of the financial revision (PRJ-T04 to PRJ-T10, PRJ-T14, PRJ-T15).
 */
@Tag("e2e")
class FinanceProjectionServiceE2E extends E2ETestBase {

    private static final long SEEDED_REVISION = 7;

    @Test
    void activationOpensOnlyProvisioningRootsAndPreservesTheirHistory() {
        runFinanceTest(fixture -> {
            UUID absent = UUID.randomUUID();
            assertEquals(ActivationOutcome.ACTIVATED, activate(fixture, absent));
            assertEquals(Optional.of(new FinancialGroup(absent, FinancialGroupState.OPEN, 0)),
                    FinanceTestFixture.root(fixture, absent));
            assertEquals(List.of(), FinanceTestFixture.actors(fixture, absent));
            // A repeated creation fact must not change the opened root.
            GroupsRecord opened = FinanceTestFixture.rootRow(fixture, absent).orElseThrow();
            assertEquals(ActivationOutcome.UNCHANGED, activate(fixture, absent));
            assertEquals(Optional.of(opened), FinanceTestFixture.rootRow(fixture, absent));

            UUID provisioning = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, provisioning, FinancialGroupState.PROVISIONING, SEEDED_REVISION);
            assertEquals(ActivationOutcome.ACTIVATED, activate(fixture, provisioning));
            GroupsRecord activated = FinanceTestFixture.rootRow(fixture, provisioning).orElseThrow();
            assertEquals(new FinancialGroup(provisioning, FinancialGroupState.OPEN, SEEDED_REVISION),
                    FinanceTestFixture.root(fixture, provisioning).orElseThrow());
            assertTrue(SEEDED_AT.isEqual(activated.getCreatedAt()), activated::toString);

            for (FinancialGroupState state : List.of(FinancialGroupState.OPEN, FinancialGroupState.CLOSED,
                    FinancialGroupState.ARCHIVED)) {
                UUID group = UUID.randomUUID();
                FinanceTestFixture.seedRoot(fixture, group, state, SEEDED_REVISION);
                GroupsRecord before = FinanceTestFixture.rootRow(fixture, group).orElseThrow();
                assertEquals(ActivationOutcome.UNCHANGED, activate(fixture, group), state.name());
                assertEquals(Optional.of(before), FinanceTestFixture.rootRow(fixture, group), state.name());
            }
        });
    }

    @Test
    void membershipFactsProvisionUnknownRootsAndRetainUnknownActorsIncludingRemovals() {
        runFinanceTest(fixture -> {
            UUID unknownGroup = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, unknownGroup, owner, 1, MembershipState.ACTIVE));
            assertEquals(Optional.of(new FinancialGroup(unknownGroup, FinancialGroupState.PROVISIONING, 0)),
                    FinanceTestFixture.root(fixture, unknownGroup));
            assertEquals(List.of(new FinancialActor(unknownGroup, owner, MembershipState.ACTIVE, 1)),
                    FinanceTestFixture.actors(fixture, unknownGroup));

            UUID existing = UUID.randomUUID();
            UUID leaver = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, existing, FinancialGroupState.OPEN, SEEDED_REVISION);
            GroupsRecord root = FinanceTestFixture.rootRow(fixture, existing).orElseThrow();
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, existing, leaver, 2, MembershipState.REMOVED));
            assertEquals(List.of(new FinancialActor(existing, leaver, MembershipState.REMOVED, 2)),
                    FinanceTestFixture.actors(fixture, existing));
            assertEquals(Optional.of(root), FinanceTestFixture.rootRow(fixture, existing));
        });
    }

    @Test
    void duplicateAndStaleFactsLeaveTheActorRowUntouchedAndANewerFactSupersedesIt() {
        runFinanceTest(fixture -> {
            UUID group = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, group, FinancialGroupState.OPEN, SEEDED_REVISION);
            FinanceTestFixture.seedActor(fixture, new FinancialActor(group, user, MembershipState.ACTIVE, 3));
            ActorsRecord seeded = FinanceTestFixture.actorRow(fixture, group, user);

            assertEquals(MembershipOutcome.DUPLICATE, apply(fixture, group, user, 3, MembershipState.ACTIVE));
            assertEquals(seeded, FinanceTestFixture.actorRow(fixture, group, user));

            assertEquals(MembershipOutcome.STALE, apply(fixture, group, user, 2, MembershipState.REMOVED));
            assertEquals(seeded, FinanceTestFixture.actorRow(fixture, group, user));

            assertEquals(MembershipOutcome.SUPERSEDED, apply(fixture, group, user, 4, MembershipState.REMOVED));
            ActorsRecord superseded = FinanceTestFixture.actorRow(fixture, group, user);
            assertEquals(true, superseded.getDeleted());
            assertEquals(4L, superseded.getUpstreamRevision());
            assertEquals(seeded.getCreatedAt(), superseded.getCreatedAt());
            assertTrue(superseded.getUpdatedAt().isAfter(seeded.getUpdatedAt()), superseded::toString);
        });
    }

    @Test
    void contradictionFailsWithItsDiagnosticsAndCommitsNothing() {
        runFinanceTest(fixture -> {
            UUID group = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, group, FinancialGroupState.OPEN, SEEDED_REVISION);
            FinanceTestFixture.seedActor(fixture, new FinancialActor(group, user, MembershipState.ACTIVE, 5));
            CommittedState before = FinanceTestFixture.committedState(fixture);

            ConflictingMembershipFactException failure = assertThrows(ConflictingMembershipFactException.class,
                    () -> apply(fixture, group, user, 5, MembershipState.REMOVED));

            assertEquals(group, failure.groupId());
            assertEquals(user, failure.userId());
            assertEquals(5, failure.revision());
            assertEquals(MembershipState.ACTIVE, failure.storedState());
            assertEquals(MembershipState.REMOVED, failure.receivedState());
            assertEquals(before, FinanceTestFixture.committedState(fixture));
        });
    }

    @Test
    void everyDeliveryOrderOfAddRemoveAddConvergesOnTheLatestState() {
        runFinanceTest(fixture -> {
            List<MembershipState> stateByRevision = List.of(
                    MembershipState.ACTIVE, MembershipState.REMOVED, MembershipState.ACTIVE);
            for (List<Long> order : permutations(List.of(1L, 2L, 3L))) {
                UUID group = UUID.randomUUID();
                UUID user = UUID.randomUUID();
                long highest = 0;
                for (long revision : order) {
                    MembershipState state = stateByRevision.get((int) revision - 1);
                    MembershipOutcome expected = highest == 0 ? MembershipOutcome.INSERTED
                            : revision > highest ? MembershipOutcome.SUPERSEDED : MembershipOutcome.STALE;
                    assertEquals(expected, apply(fixture, group, user, revision, state), order::toString);
                    highest = Math.max(highest, revision);
                    // Each intermediate state reflects the highest revision delivered so far, so
                    // leave-before-join passes through a removed tombstone.
                    assertEquals(List.of(new FinancialActor(group, user, stateByRevision.get((int) highest - 1),
                            highest)), FinanceTestFixture.actors(fixture, group), order::toString);
                }
                assertEquals(List.of(new FinancialActor(group, user, MembershipState.ACTIVE, 3)),
                        FinanceTestFixture.actors(fixture, group), order::toString);
            }
        });
    }

    @Test
    void revisionsAreComparedPerActorWithinAGroup() {
        runFinanceTest(fixture -> {
            UUID group = UUID.randomUUID();
            UUID first = new UUID(0, 1);
            UUID second = new UUID(0, 2);
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, group, second, 3, MembershipState.ACTIVE));
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, group, first, 2, MembershipState.ACTIVE));
            assertEquals(MembershipOutcome.STALE, apply(fixture, group, first, 1, MembershipState.REMOVED));
            assertEquals(List.of(new FinancialActor(group, first, MembershipState.ACTIVE, 2),
                    new FinancialActor(group, second, MembershipState.ACTIVE, 3)),
                    FinanceTestFixture.actors(fixture, group));
        });
    }

    @Test
    void groupCreationAndMembershipFactsConvergeInEveryArrivalOrder() {
        runFinanceTest(fixture -> {
            UUID owner = new UUID(0, 1);
            UUID member = new UUID(0, 2);

            UUID creationFirst = UUID.randomUUID();
            assertEquals(ActivationOutcome.ACTIVATED, activate(fixture, creationFirst));
            assertRoot(fixture, creationFirst, FinancialGroupState.OPEN);
            assertEquals(List.of(), FinanceTestFixture.actors(fixture, creationFirst));
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, creationFirst, owner, 1, MembershipState.ACTIVE));
            assertConverged(fixture, creationFirst, List.of(owner));

            UUID ownerFirst = UUID.randomUUID();
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, ownerFirst, owner, 1, MembershipState.ACTIVE));
            assertRoot(fixture, ownerFirst, FinancialGroupState.PROVISIONING);
            assertEquals(List.of(new FinancialActor(ownerFirst, owner, MembershipState.ACTIVE, 1)),
                    FinanceTestFixture.actors(fixture, ownerFirst));
            assertEquals(ActivationOutcome.ACTIVATED, activate(fixture, ownerFirst));
            assertConverged(fixture, ownerFirst, List.of(owner));

            // A later member's fact can overtake both the creation fact and the owner's fact.
            UUID memberThenCreation = UUID.randomUUID();
            assertEquals(MembershipOutcome.INSERTED,
                    apply(fixture, memberThenCreation, member, 2, MembershipState.ACTIVE));
            assertEquals(ActivationOutcome.ACTIVATED, activate(fixture, memberThenCreation));
            assertEquals(MembershipOutcome.INSERTED,
                    apply(fixture, memberThenCreation, owner, 1, MembershipState.ACTIVE));
            assertConverged(fixture, memberThenCreation, List.of(owner, member));

            UUID memberThenOwner = UUID.randomUUID();
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, memberThenOwner, member, 2, MembershipState.ACTIVE));
            assertEquals(MembershipOutcome.INSERTED, apply(fixture, memberThenOwner, owner, 1, MembershipState.ACTIVE));
            assertRoot(fixture, memberThenOwner, FinancialGroupState.PROVISIONING);
            assertEquals(ActivationOutcome.ACTIVATED, activate(fixture, memberThenOwner));
            assertConverged(fixture, memberThenOwner, List.of(owner, member));
        });
    }

    @Test
    void failureAfterTheActorWriteCommitsNeitherRootNorActorAndReprocessingCommitsBothOnce() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            ActorWriteFailure failure = fixture.context().getBean(ActorWriteFailure.class);

            failure.arm();
            assertSame(failure.injected(), assertThrows(IllegalStateException.class,
                    () -> apply(fixture, group, user, 1, MembershipState.ACTIVE)));
            assertEquals(new CommittedState(List.of(), List.of()), FinanceTestFixture.committedState(fixture));

            assertEquals(MembershipOutcome.INSERTED, apply(fixture, group, user, 1, MembershipState.ACTIVE));
            assertEquals(MembershipOutcome.DUPLICATE, apply(fixture, group, user, 1, MembershipState.ACTIVE));
            assertRoot(fixture, group, FinancialGroupState.PROVISIONING);
            assertEquals(List.of(new FinancialActor(group, user, MembershipState.ACTIVE, 1)),
                    FinanceTestFixture.actors(fixture, group));
            assertEquals(1, FinanceTestFixture.committedState(fixture).roots().size());
        }, ProductionEntityScanConfiguration.class, ActorWriteFailureConfiguration.class);
    }

    @Test
    void noOutcomeChangesTheFinancialRevisionOrAnotherGroup() {
        runFinanceTest(fixture -> {
            UUID group = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            UUID bystander = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, group, FinancialGroupState.PROVISIONING, SEEDED_REVISION);
            FinanceTestFixture.seedRoot(fixture, bystander, FinancialGroupState.OPEN, SEEDED_REVISION);
            FinanceTestFixture.seedActor(fixture, new FinancialActor(bystander, user, MembershipState.ACTIVE, 9));
            CommittedState initial = FinanceTestFixture.committedState(fixture);
            List<GroupsRecord> bystanderRoots = rowsOf(initial.roots(), bystander);
            List<Enum<?>> outcomes = new ArrayList<>();

            outcomes.add(apply(fixture, group, user, 1, MembershipState.ACTIVE));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);
            outcomes.add(apply(fixture, group, user, 2, MembershipState.REMOVED));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);
            outcomes.add(apply(fixture, group, user, 2, MembershipState.REMOVED));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);
            outcomes.add(apply(fixture, group, user, 1, MembershipState.ACTIVE));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);
            assertThrows(ConflictingMembershipFactException.class,
                    () -> apply(fixture, group, user, 2, MembershipState.ACTIVE));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);
            outcomes.add(activate(fixture, group));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);
            outcomes.add(activate(fixture, group));
            assertRevisionAndBystanderUnchanged(fixture, group, bystander, bystanderRoots, initial);

            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.SUPERSEDED, MembershipOutcome.DUPLICATE,
                    MembershipOutcome.STALE, ActivationOutcome.ACTIVATED, ActivationOutcome.UNCHANGED), outcomes);
            assertEquals(Optional.of(new FinancialGroup(group, FinancialGroupState.OPEN, SEEDED_REVISION)),
                    FinanceTestFixture.root(fixture, group));
        });
    }

    /** Arms a one-shot failure of the next actor insert, raised after the delegate's write succeeded. */
    static final class ActorWriteFailure {
        private final IllegalStateException injected = new IllegalStateException("Injected failure after actor insert");
        private final AtomicBoolean armed = new AtomicBoolean();

        IllegalStateException injected() {
            return injected;
        }

        void arm() {
            armed.set(true);
        }

        void afterInsert() {
            if (armed.compareAndSet(true, false)) {
                throw injected;
            }
        }
    }

    /**
     * Replaces the service's actor store with a delegate to the production adapter that can fail
     * after a successful insert, within the same segment. Adds no production hook.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ActorWriteFailureConfiguration {

        @Bean
        ActorWriteFailure actorWriteFailure() {
            return new ActorWriteFailure();
        }

        @Bean
        @Primary
        @Scope("operation")
        FinancialActorStore failingActorStore(@Qualifier("jooqFinancialActorStore") FinancialActorStore delegate,
                ActorWriteFailure failure) {
            return new FinancialActorStore() {
                @Override
                public Optional<FinancialActor> find(UUID groupId, UUID userId) {
                    return delegate.find(groupId, userId);
                }

                @Override
                public void insert(FinancialActor actor) {
                    delegate.insert(actor);
                    failure.afterInsert();
                }

                @Override
                public void supersede(FinancialActor current, FinancialActor next) {
                    delegate.supersede(current, next);
                }
            };
        }
    }

    private void runFinanceTest(TestCallbacks.Action<IntegrationTestContext> test) {
        runTest(test, ProductionEntityScanConfiguration.class);
    }


    private static void assertRoot(IntegrationTestContext fixture, UUID group, FinancialGroupState state)
            throws SQLException {
        assertEquals(Optional.of(new FinancialGroup(group, state, 0)), FinanceTestFixture.root(fixture, group));
    }

    /** An open root at revision zero whose actors are exactly the given users at revisions 1, 2, ... */
    private static void assertConverged(IntegrationTestContext fixture, UUID group, List<UUID> users)
            throws SQLException {
        assertRoot(fixture, group, FinancialGroupState.OPEN);
        List<FinancialActor> expected = new ArrayList<>();
        for (int index = 0; index < users.size(); index++) {
            expected.add(new FinancialActor(group, users.get(index), MembershipState.ACTIVE, index + 1));
        }
        assertEquals(expected, FinanceTestFixture.actors(fixture, group));
    }

    private static void assertRevisionAndBystanderUnchanged(IntegrationTestContext fixture, UUID group,
            UUID bystander, List<GroupsRecord> bystanderRoots, CommittedState initial) throws SQLException {
        CommittedState current = FinanceTestFixture.committedState(fixture);
        assertEquals(SEEDED_REVISION, rowsOf(current.roots(), group).getFirst().getCurrentRevision());
        assertEquals(bystanderRoots, rowsOf(current.roots(), bystander));
        assertEquals(actorRowsOf(initial.actors(), bystander), actorRowsOf(current.actors(), bystander));
    }

    private static List<GroupsRecord> rowsOf(List<GroupsRecord> roots, UUID group) {
        return roots.stream().filter(root -> root.getId().equals(group)).toList();
    }

    private static List<ActorsRecord> actorRowsOf(List<ActorsRecord> actors, UUID group) {
        return actors.stream().filter(actor -> actor.getGroupId().equals(group)).toList();
    }

    private static List<List<Long>> permutations(List<Long> values) {
        if (values.isEmpty()) {
            return List.of(List.of());
        }
        List<List<Long>> result = new ArrayList<>();
        for (Long head : values) {
            List<Long> rest = new ArrayList<>(values);
            rest.remove(head);
            for (List<Long> tail : permutations(rest)) {
                List<Long> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
