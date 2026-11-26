package de.hsh.favs.sharewise.finance.internal.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Operation protocol of the projection service over recording fakes (PRJ-T03): one segment per
 * operation, the root lock before any actor read, at most one write and the outcome's decision.
 */
class FinanceProjectionServiceTests {

    private static final UUID GROUP = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final List<String> ACTOR_READ = List.of("begin", "groups.ensureAndLock", "actors.find");

    private final RecordingFinanceStores stores = new RecordingFinanceStores();
    private final FinanceProjectionService service =
            new FinanceProjectionService(stores.transactions(), stores.groups(), stores.actors());

    @Test
    void activationOfAnAbsentRootProvisionsAndOpensItInOneCommittedSegment() {
        assertEquals(ActivationOutcome.ACTIVATED, service.activate(new ActivateFinancialGroup(GROUP)));

        assertEquals(List.of("begin", "groups.ensureAndLock", "groups.activate", "COMMIT"), stores.journal());
        assertEquals(Optional.of(new FinancialGroup(GROUP, FinancialGroupState.OPEN, 0)), stores.root(GROUP));
        assertEquals(0, stores.actorCount());
    }

    @Test
    void activationOfAProvisioningRootPreservesItsRevision() {
        stores.seedRoot(new FinancialGroup(GROUP, FinancialGroupState.PROVISIONING, 9));

        assertEquals(ActivationOutcome.ACTIVATED, service.activate(new ActivateFinancialGroup(GROUP)));

        assertEquals(List.of("begin", "groups.ensureAndLock", "groups.activate", "COMMIT"), stores.journal());
        assertEquals(Optional.of(new FinancialGroup(GROUP, FinancialGroupState.OPEN, 9)), stores.root(GROUP));
    }

    @Test
    void activationLeavesEveryLaterLifecycleStateUntouchedAndAbstains() {
        for (FinancialGroupState state : List.of(FinancialGroupState.OPEN, FinancialGroupState.CLOSED,
                FinancialGroupState.ARCHIVED)) {
            FinancialGroup root = new FinancialGroup(GROUP, state, 4);
            stores.seedRoot(root);
            stores.resetJournal();

            assertEquals(ActivationOutcome.UNCHANGED, service.activate(new ActivateFinancialGroup(GROUP)), state.name());

            assertEquals(List.of("begin", "groups.ensureAndLock", "ABSTAIN"), stores.journal(), state.name());
            assertEquals(Optional.of(root), stores.root(GROUP), state.name());
        }
    }

    @Test
    void unknownActorIsInsertedAfterTheRootLockAndCommits() {
        for (MembershipState state : MembershipState.values()) {
            UUID user = UUID.randomUUID();
            stores.resetJournal();

            assertEquals(MembershipOutcome.INSERTED, apply(user, 2, state));

            assertEquals(concat(ACTOR_READ, "actors.insert", "COMMIT"), stores.journal());
            assertEquals(Optional.of(new FinancialActor(GROUP, user, state, 2)), stores.actor(GROUP, user));
        }
        // The first fact provisioned the root without activating it.
        assertEquals(Optional.of(new FinancialGroup(GROUP, FinancialGroupState.PROVISIONING, 0)), stores.root(GROUP));
    }

    @Test
    void newerRevisionSupersedesTheRetainedActorAndCommits() {
        FinancialGroup root = seedRoot(FinancialGroupState.OPEN);
        stores.seedActor(new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 3));

        assertEquals(MembershipOutcome.SUPERSEDED, apply(USER, 4, MembershipState.REMOVED));

        assertEquals(concat(ACTOR_READ, "actors.supersede", "COMMIT"), stores.journal());
        assertEquals(Optional.of(new FinancialActor(GROUP, USER, MembershipState.REMOVED, 4)), stores.actor(GROUP, USER));
        assertEquals(Optional.of(root), stores.root(GROUP));
    }

    @Test
    void duplicateAndStaleFactsWriteNothingAndAbstain() {
        seedRoot(FinancialGroupState.PROVISIONING);
        FinancialActor retained = new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 3);
        stores.seedActor(retained);

        assertEquals(MembershipOutcome.DUPLICATE, apply(USER, 3, MembershipState.ACTIVE));
        assertEquals(concat(ACTOR_READ, "ABSTAIN"), stores.journal());

        stores.resetJournal();
        assertEquals(MembershipOutcome.STALE, apply(USER, 2, MembershipState.REMOVED));
        assertEquals(concat(ACTOR_READ, "ABSTAIN"), stores.journal());

        assertEquals(Optional.of(retained), stores.actor(GROUP, USER));
    }

    @Test
    void contradictionThrowsFromInsideTheSegmentWithoutAWrite() {
        FinancialGroup root = seedRoot(FinancialGroupState.OPEN);
        FinancialActor retained = new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 5);
        stores.seedActor(retained);

        ConflictingMembershipFactException failure = assertThrows(ConflictingMembershipFactException.class,
                () -> apply(USER, 5, MembershipState.REMOVED));

        assertEquals(5, failure.revision());
        assertEquals(MembershipState.ACTIVE, failure.storedState());
        assertEquals(MembershipState.REMOVED, failure.receivedState());
        assertEquals(concat(ACTOR_READ, "failed"), stores.journal());
        assertEquals(Optional.of(retained), stores.actor(GROUP, USER));
        assertEquals(Optional.of(root), stores.root(GROUP));
    }

    @Test
    void storeFailuresPropagateUnchangedAndDiscardTheProvisionedRoot() {
        IllegalStateException insertFailure = new IllegalStateException("insert failed");
        stores.failOn("actors.insert", insertFailure);
        assertSame(insertFailure, assertThrows(IllegalStateException.class,
                () -> apply(USER, 1, MembershipState.ACTIVE)));
        assertEquals(concat(ACTOR_READ, "actors.insert", "failed"), stores.journal());
        assertEquals(Optional.empty(), stores.root(GROUP));

        stores.resetJournal();
        IllegalStateException activationFailure = new IllegalStateException("activation failed");
        stores.failOn("groups.activate", activationFailure);
        assertSame(activationFailure, assertThrows(IllegalStateException.class,
                () -> service.activate(new ActivateFinancialGroup(GROUP))));
        assertEquals(List.of("begin", "groups.ensureAndLock", "groups.activate", "failed"), stores.journal());
        assertEquals(Optional.empty(), stores.root(GROUP));

        // The service does not retry; a fresh invocation reprocesses the fact from committed state.
        stores.resetJournal();
        assertEquals(MembershipOutcome.INSERTED, apply(USER, 1, MembershipState.ACTIVE));
        assertEquals(concat(ACTOR_READ, "actors.insert", "COMMIT"), stores.journal());
    }

    @Test
    void rejectsNullCommandsBeforeStartingASegment() {
        assertThrows(NullPointerException.class, () -> service.activate(null));
        assertThrows(NullPointerException.class, () -> service.applyMembershipChange(null));
        assertEquals(List.of(), stores.journal());
    }

    private MembershipOutcome apply(UUID user, long revision, MembershipState state) {
        return service.applyMembershipChange(new ApplyMembershipChange(GROUP, user, revision, state));
    }

    private FinancialGroup seedRoot(FinancialGroupState state) {
        FinancialGroup root = new FinancialGroup(GROUP, state, 6);
        stores.seedRoot(root);
        return root;
    }

    private static List<String> concat(List<String> prefix, String... rest) {
        return Stream.concat(prefix.stream(), Arrays.stream(rest)).toList();
    }
}
