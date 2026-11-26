package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.GroupConcurrencyFixture.LOCK_WAIT_TIMEOUT;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupConcurrencyFixture.lockGroup;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ALICE;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.BOB;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.change;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.create;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.invitation;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.membershipRevision;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ok;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.query;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.redeem;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.replace;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.workspace;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.awaitLockWaiters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.events.GroupDeliveryFixture;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemption;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeStatus;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.BoundedStatementsConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Durable membership changes, identity, membership revisions and concurrent administrator invariants. */
@Tag("e2e")
class GroupMembershipE2E extends E2ETestBase {

    /** DLV-T05: only committed membership insertions and deletions advance the revision. */
    @Test
    void committedMembershipChangesAdvanceTheRevisionAndOtherOperationsDoNot() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            assertEquals(1, membershipRevision(fixture, group));

            CreatedInvitation link = invitation(fixture, group, null, null);
            assertEquals(InvitationRedemption.Outcome.JOINED, ok(redeem(fixture, BOB, link)).outcome());
            assertEquals(2, membershipRevision(fixture, group));

            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            ok(change(fixture, ALICE, group, bobId, new MembershipChange.SetRole(GroupRole.ADMIN)));
            assertEquals(2, membershipRevision(fixture, group));

            ok(change(fixture, BOB, group, bobId, new MembershipChange.Remove()));
            assertEquals(3, membershipRevision(fixture, group));

            assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, ok(redeem(fixture, ALICE, link)).outcome());
            assertEquals(3, membershipRevision(fixture, group));

            assertEquals(Result.err(new GroupFailure.LastMember()),
                change(fixture, ALICE, group, aliceId, new MembershipChange.Remove()));
            assertEquals(3, membershipRevision(fixture, group));
            assertEquals(List.of(0L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    /**
     * DLV-R03 and DLV-T07: the row lock serializes concurrent joins, so each commits and publishes
     * its own revision, and Finance retains each joiner at the revision committed with the join.
     */
    @Test
    void concurrentJoinsCommitDistinctConsecutiveRevisions() {
        runTest(fixture -> {
            UUID carol = UUID.randomUUID();
            GroupTestFixture.provision(fixture, ALICE, BOB, carol);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, null);
            try (ExecutorService callers = Executors.newFixedThreadPool(2);
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<InvitationRedemption, GroupFailure>> bob = callers.submit(() -> redeem(fixture, BOB, link));
                Future<Result<InvitationRedemption, GroupFailure>> other = callers.submit(() -> redeem(fixture, carol, link));
                awaitLockWaiters(fixture, holder, 2, LOCK_WAIT_TIMEOUT);
                holder.commit();
                for (Future<Result<InvitationRedemption, GroupFailure>> joiner : List.of(bob, other)) {
                    assertEquals(InvitationRedemption.Outcome.JOINED,
                        ok(joiner.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).outcome());
                }
            }
            assertEquals(3, membershipRevision(fixture, group));
            assertEquals(List.of(3L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));

            GroupDeliveryFixture.awaitDelivered(fixture);
            Map<UUID, Long> revisions = new HashMap<>();
            for (FinancialActor actor : FinanceTestFixture.actors(fixture, group)) {
                assertEquals(MembershipState.ACTIVE, actor.state(), actor::toString);
                revisions.put(actor.userId(), actor.upstreamRevision());
            }
            assertEquals(Set.of(ALICE, BOB, carol), revisions.keySet());
            assertEquals(1L, revisions.get(ALICE));
            assertEquals(Set.of(2L, 3L), Set.of(revisions.get(BOB), revisions.get(carol)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void simultaneousAdministratorDeparturesCannotRemoveTheLastMember() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            try (ExecutorService callers = Executors.newFixedThreadPool(2);
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<MembershipChangeStatus, GroupFailure>> alice = callers.submit(() ->
                    change(fixture, ALICE, group, aliceId, new MembershipChange.Remove()));
                Future<Result<MembershipChangeStatus, GroupFailure>> bob = callers.submit(() ->
                    change(fixture, BOB, group, bobId, new MembershipChange.Remove()));
                awaitLockWaiters(fixture, holder, 2, LOCK_WAIT_TIMEOUT);
                holder.commit();
                List<Result<MembershipChangeStatus, GroupFailure>> results = List.of(
                    alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.LastMember())), results::toString);
            }
            assertEquals(List.of("admin"), query(fixture, "SELECT role FROM groups.group_members", row -> row.getString(1)));
            assertEquals(List.of(0L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void competingDemotionsObserveCommittedAuthorizationAndKeepAnAdministrator() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            try (ExecutorService callers = Executors.newFixedThreadPool(2);
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<MembershipChangeStatus, GroupFailure>> alice = callers.submit(() ->
                    change(fixture, ALICE, group, bobId, new MembershipChange.SetRole(GroupRole.MEMBER)));
                Future<Result<MembershipChangeStatus, GroupFailure>> bob = callers.submit(() ->
                    change(fixture, BOB, group, aliceId, new MembershipChange.SetRole(GroupRole.MEMBER)));
                awaitLockWaiters(fixture, holder, 2, LOCK_WAIT_TIMEOUT);
                holder.commit();
                List<Result<MembershipChangeStatus, GroupFailure>> results = List.of(
                    alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.AdministratorRequired())), results::toString);
            }
            assertEquals(List.of("admin", "member"), query(fixture,
                "SELECT role::text FROM groups.group_members ORDER BY role::text", row -> row.getString(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void promotionAndRejoinPreserveMembershipIdentityAndMetadataConcurrency() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.MEMBER);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            ok(change(fixture, ALICE, group, bobId, new MembershipChange.SetRole(GroupRole.ADMIN)));
            assertEquals(GroupRole.ADMIN, workspace(fixture, ALICE, group).group().callerRole());
            assertEquals(GroupRole.ADMIN, workspace(fixture, BOB, group).group().callerRole());
            assertEquals(bobId, workspace(fixture, BOB, group).callerMembershipId());
            assertEquals(1, ok(replace(fixture, BOB, group, "Renamed", 0)).version());
            ok(change(fixture, ALICE, group, aliceId, new MembershipChange.Remove()));
            GroupTestFixture.addMember(fixture, group, ALICE, GroupRole.MEMBER);
            assertEquals(Result.err(new GroupFailure.MemberNotFound()), change(fixture, BOB, group, aliceId,
                new MembershipChange.Remove()));
            GroupWorkspace joined = workspace(fixture, ALICE, group);
            assertEquals(1, joined.group().version());
            assertEquals(2, joined.group().memberCount());
            assertTrue(!aliceId.equals(joined.callerMembershipId()));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void failedPromotionLeavesMembershipUnchanged() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.MEMBER);
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            // Force a database rejection of the target member's promotion.
            try (Connection migrator = fixture.database().connectAsMigrator();
                 Statement ddl = migrator.createStatement()) {
                ddl.execute("ALTER TABLE groups.group_members ADD CONSTRAINT test_bob_member CHECK "
                    + "(user_id <> '" + BOB + "'::uuid OR role = 'member')");
            }
            assertThrows(RuntimeException.class, () -> change(fixture, ALICE, group, bobId,
                new MembershipChange.SetRole(GroupRole.ADMIN)));
            assertEquals(GroupRole.ADMIN, workspace(fixture, ALICE, group).group().callerRole());
            assertEquals(GroupRole.MEMBER, workspace(fixture, BOB, group).group().callerRole());
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void competingRemovalsCannotDeleteBothAdministrators() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            try (ExecutorService callers = Executors.newFixedThreadPool(2);
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<MembershipChangeStatus, GroupFailure>> alice = callers.submit(() ->
                    change(fixture, ALICE, group, bobId, new MembershipChange.Remove()));
                Future<Result<MembershipChangeStatus, GroupFailure>> bob = callers.submit(() ->
                    change(fixture, BOB, group, aliceId, new MembershipChange.Remove()));
                awaitLockWaiters(fixture, holder, 2, LOCK_WAIT_TIMEOUT);
                holder.commit();
                List<Result<MembershipChangeStatus, GroupFailure>> results = List.of(
                    alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.GroupNotFound())), results::toString);
            }
            assertEquals(List.of("admin"), query(fixture, "SELECT role FROM groups.group_members", row -> row.getString(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }
}
