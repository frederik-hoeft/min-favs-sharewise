package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.group.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMetadataService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.group.ReplaceGroupMetadataCommand;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreateInvitationCommand;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.invitation.GroupInvitationService;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemption;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipService;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeCommand;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeStatus;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestScope;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Group operations with real commits on a disposable database: durable creation and
 * replacement, and the group mutation protocol under concurrency (GM-T01, GM-T02, GM-T09,
 * GM-T12, GM-T13). Callers are bound through the test identity fixture; HTTP ingress is
 * covered separately.
 */
@Tag("e2e")
class GroupOperationsE2E extends E2ETestBase {

    private static final UUID ALICE = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be301");
    private static final UUID BOB = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be302");
    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(15);

    @Test
    void creationCommitsTheGroupWithItsCreatorAsOnlyAdministrator() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE);

            GroupDetails created = ok(create(fixture, ALICE, "Flat share"));

            assertEquals(List.of("Flat share|0"), query(fixture,
                "SELECT name, version FROM groups.groups", row -> row.getString(1) + "|" + row.getLong(2)));
            assertEquals(List.of(created.id() + "|" + ALICE + "|admin"), query(fixture,
                "SELECT group_id, user_id, role FROM groups.group_members",
                row -> row.getString(1) + "|" + row.getString(2) + "|" + row.getString(3)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void aRejectedCreatorMembershipLeavesNoGroupRowBehind() {
        runTest(fixture -> {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> inOperation(fixture, scope -> {
                GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
                return scope.resolve(Transactions.class).execute(() ->
                    TransactionOutcome.commit(creation.createWithAdministrator(UUID.randomUUID(), metadata("Trip"))));
            }));

            assertTrue(failure.getMessage().contains("group_members_user_id_fkey"), failure::toString);
            assertEquals(List.of(0L), query(fixture, "SELECT count(*) FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class);
    }

    @Test
    void administratorReplacementIsCommittedAndReadBack() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();

            GroupDetails replaced = ok(replace(fixture, ALICE, group, "Summer trip", 0));
            GroupDetails read = ok(withUser(ALICE, () -> dispatcher(fixture)
                .dispatch(GroupQueryService.class, service -> service.get(group))));

            assertEquals(1, replaced.version());
            assertEquals(replaced, read);
            assertEquals(List.of("Summer trip|1"), query(fixture,
                "SELECT name, version FROM groups.groups", row -> row.getString(1) + "|" + row.getLong(2)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void concurrentReplacementsWithTheSameVersionSerializeOnTheGroupLock() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);

            List<Result<GroupDetails, GroupFailure>> results = new ArrayList<>();
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newFixedThreadPool(2)) {
                Future<Result<GroupDetails, GroupFailure>> alice =
                    callers.submit(() -> replace(fixture, ALICE, group, "Alice's trip", 0));
                Future<Result<GroupDetails, GroupFailure>> bob =
                    callers.submit(() -> replace(fixture, BOB, group, "Bob's trip", 0));
                awaitLockWaiters(fixture, 2);
                holder.commit();
                results.add(alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                results.add(bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().filter(Result::isSuccess).count(), results::toString);
            assertEquals(1, results.stream().filter(new Result.Err<GroupDetails, GroupFailure>(
                new GroupFailure.VersionConflict())::equals).count(), results::toString);
            assertEquals(List.of(1L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void replacementWaitingForTheLockObservesAConcurrentlyCommittedDemotion() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);

            Result<GroupDetails, GroupFailure> result;
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newSingleThreadExecutor()) {
                Future<Result<GroupDetails, GroupFailure>> replacement =
                    callers.submit(() -> replace(fixture, ALICE, group, "Renamed", 0));
                awaitLockWaiters(fixture, 1);
                GroupTestFixture.changeRole(DSL.using(holder, SQLDialect.POSTGRES), group, ALICE, GroupRole.MEMBER);
                holder.commit();
                result = replacement.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }

            assertEquals(Result.err(new GroupFailure.AdministratorRequired()), result);
            assertEquals(List.of("Trip|0"), query(fixture,
                "SELECT name, version FROM groups.groups", row -> row.getString(1) + "|" + row.getLong(2)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void simultaneousAdministratorDeparturesCannotRemoveTheLastMember() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newFixedThreadPool(2)) {
                Future<Result<MembershipChangeStatus, GroupFailure>> alice = callers.submit(() ->
                    change(fixture, ALICE, group, aliceId, new MembershipChange.Remove()));
                Future<Result<MembershipChangeStatus, GroupFailure>> bob = callers.submit(() ->
                    change(fixture, BOB, group, bobId, new MembershipChange.Remove()));
                awaitLockWaiters(fixture, 2);
                holder.commit();
                List<Result<MembershipChangeStatus, GroupFailure>> results = List.of(
                    alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.LastMember())), results::toString);
            }
            assertEquals(List.of("admin"), query(fixture, "SELECT role FROM groups.group_members", row -> row.getString(1)));
            assertEquals(List.of(0L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void competingDemotionsObserveCommittedAuthorizationAndKeepAnAdministrator() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newFixedThreadPool(2)) {
                Future<Result<MembershipChangeStatus, GroupFailure>> alice = callers.submit(() ->
                    change(fixture, ALICE, group, bobId, new MembershipChange.SetRole(GroupRole.MEMBER)));
                Future<Result<MembershipChangeStatus, GroupFailure>> bob = callers.submit(() ->
                    change(fixture, BOB, group, aliceId, new MembershipChange.SetRole(GroupRole.MEMBER)));
                awaitLockWaiters(fixture, 2);
                holder.commit();
                List<Result<MembershipChangeStatus, GroupFailure>> results = List.of(
                    alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.AdministratorRequired())), results::toString);
            }
            assertEquals(List.of("admin", "member"), query(fixture,
                "SELECT role::text FROM groups.group_members ORDER BY role::text", row -> row.getString(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
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
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newFixedThreadPool(2)) {
                Future<Result<MembershipChangeStatus, GroupFailure>> alice = callers.submit(() ->
                    change(fixture, ALICE, group, bobId, new MembershipChange.Remove()));
                Future<Result<MembershipChangeStatus, GroupFailure>> bob = callers.submit(() ->
                    change(fixture, BOB, group, aliceId, new MembershipChange.Remove()));
                awaitLockWaiters(fixture, 2);
                holder.commit();
                List<Result<MembershipChangeStatus, GroupFailure>> results = List.of(
                    alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.GroupNotFound())), results::toString);
            }
            assertEquals(List.of("admin"), query(fixture, "SELECT role FROM groups.group_members", row -> row.getString(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void unlimitedInvitationAllowsRejoiningWithoutResettingUsageOrMembershipIdentity() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, null);
            assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, ok(redeem(fixture, ALICE, link)).outcome());
            assertEquals(InvitationRedemption.Outcome.JOINED, ok(redeem(fixture, BOB, link)).outcome());
            UUID first = workspace(fixture, BOB, group).callerMembershipId();
            assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, ok(redeem(fixture, BOB, link)).outcome());
            ok(change(fixture, BOB, group, first, new MembershipChange.Remove()));
            assertEquals(InvitationRedemption.Outcome.JOINED, ok(redeem(fixture, BOB, link)).outcome());
            GroupWorkspace current = workspace(fixture, BOB, group);
            assertTrue(!first.equals(current.callerMembershipId()));
            assertEquals(GroupRole.MEMBER, current.group().callerRole());
            assertEquals(GroupRole.ADMIN, workspace(fixture, ALICE, group).group().callerRole());
            assertEquals(List.of(2), query(fixture, "SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void singleUseInvitationSerializesCompetingRedemptions() {
        runTest(fixture -> {
            UUID carol = UUID.randomUUID();
            GroupTestFixture.provision(fixture, ALICE, BOB, carol);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, 1);
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newFixedThreadPool(2)) {
                Future<Result<InvitationRedemption, GroupFailure>> bob = callers.submit(() -> redeem(fixture, BOB, link));
                Future<Result<InvitationRedemption, GroupFailure>> other = callers.submit(() -> redeem(fixture, carol, link));
                awaitLockWaiters(fixture, 2);
                holder.commit();
                List<Result<InvitationRedemption, GroupFailure>> results = List.of(
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS), other.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.InvitationUnavailable())));
            }
            assertEquals(List.of(1), query(fixture, "SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
            assertEquals(List.of(2L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
            assertEquals(List.of(0L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void redemptionRereadsInvitationAfterRevocationCommits() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, null);
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newSingleThreadExecutor()) {
                Future<Result<InvitationRedemption, GroupFailure>> redemption = callers.submit(() -> redeem(fixture, BOB, link));
                awaitLockWaiters(fixture, 1);
                try (PreparedStatement revoke = holder.prepareStatement("DELETE FROM groups.invitations WHERE id = ?")) {
                    revoke.setObject(1, link.invitation().id());
                    assertEquals(1, revoke.executeUpdate());
                }
                holder.commit();
                assertEquals(Result.err(new GroupFailure.InvitationUnavailable()), redemption.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void invitationExpiresWhileRedemptionWaitsForGroupLock() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            // Obtain a deadline using the database clock, not the test JVM clock.
            Instant deadline = query(fixture, "SELECT clock_timestamp() + interval '5 seconds'",
                row -> row.getObject(1, OffsetDateTime.class).toInstant()).getFirst();
            CreatedInvitation link = invitation(fixture, group, deadline, null);
            try (Connection holder = lockGroup(fixture, group);
                 ExecutorService callers = Executors.newSingleThreadExecutor()) {
                Future<Result<InvitationRedemption, GroupFailure>> redemption = callers.submit(() -> redeem(fixture, BOB, link));
                awaitLockWaiters(fixture, 1);
                // Ensure this actually exercises a request whose transaction began before expiry.
                try (PreparedStatement started = holder.prepareStatement("""
                        SELECT xact_start < ? FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'""")) {
                    started.setObject(1, deadline.atOffset(ZoneOffset.UTC));
                    try (ResultSet rows = started.executeQuery()) {
                        assertTrue(rows.next() && rows.getBoolean(1), "redemption must begin before expiry");
                    }
                }
                // Wait until the authoritative database clock passes the stored deadline.
                try (PreparedStatement wait = holder.prepareStatement("SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - clock_timestamp()))))")) {
                    wait.setObject(1, deadline.atOffset(ZoneOffset.UTC));
                    wait.execute();
                }
                holder.commit();
                assertEquals(Result.err(new GroupFailure.InvitationUnavailable()), redemption.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            assertEquals(List.of(0), query(fixture, "SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    @Test
    void invitationCountFailureRollsBackMembershipInsert() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, null);
            // Add a real database constraint in the disposable schema to force the second write to fail.
            try (Connection connection = fixture.database().connectAsMigrator(); Statement ddl = connection.createStatement()) {
                ddl.execute("ALTER TABLE groups.invitations ADD CONSTRAINT test_no_consumption CHECK (use_count = 0)");
            }
            assertThrows(RuntimeException.class, () -> redeem(fixture, BOB, link));
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
            assertEquals(List.of(0), query(fixture, "SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class);
    }

    private static CreatedInvitation invitation(IntegrationTestContext fixture, UUID group, Instant expires, Integer limit) {
        return ok(withUser(ALICE, () -> dispatcher(fixture).dispatch(GroupInvitationService.class,
            service -> service.create(new CreateInvitationCommand(group, expires, limit)))));
    }

    private static Result<InvitationRedemption, GroupFailure> redeem(IntegrationTestContext fixture, UUID user, CreatedInvitation link) {
        return withUser(user, () -> dispatcher(fixture).dispatch(GroupInvitationService.class,
            service -> service.redeem(link.token().value())));
    }

    private static GroupWorkspace workspace(IntegrationTestContext fixture, UUID caller, UUID group) {
        return ok(withUser(caller, () -> dispatcher(fixture).dispatch(GroupQueryService.class,
            service -> service.workspace(group))));
    }

    private static Result<MembershipChangeStatus, GroupFailure> change(IntegrationTestContext fixture, UUID caller,
            UUID group, UUID target, MembershipChange change) {
        return withUser(caller, () -> dispatcher(fixture).dispatch(GroupMembershipService.class,
            service -> service.change(new MembershipChangeCommand(group, target, change))));
    }

    private static Result<GroupDetails, GroupFailure> create(IntegrationTestContext fixture, UUID caller, String name) {
        return withUser(caller, () -> dispatcher(fixture).dispatch(GroupCreationService.class,
            service -> service.create(new CreateGroupCommand(name, null))));
    }

    private static Result<GroupDetails, GroupFailure> replace(
            IntegrationTestContext fixture, UUID caller, UUID group, String name, long expectedVersion) {
        ReplaceGroupMetadataCommand command = new ReplaceGroupMetadataCommand(group, name, null, expectedVersion);
        return withUser(caller, () -> dispatcher(fixture).dispatch(GroupMetadataService.class,
            service -> service.replace(command)));
    }

    /** Holds the group row lock on an independent connection until the caller commits it. */
    private static Connection lockGroup(IntegrationTestContext fixture, UUID group) throws SQLException {
        Connection holder = fixture.connectAsApplication();
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM groups.groups WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, group);
                try (ResultSet locked = lock.executeQuery()) {
                    assertTrue(locked.next(), "group to lock must exist");
                }
            }
            return holder;
        } catch (SQLException | RuntimeException failure) {
            holder.close();
            throw failure;
        }
    }

    /** Waits until the expected number of other sessions are blocked on a lock in this database. */
    private static void awaitLockWaiters(IntegrationTestContext fixture, int expected) throws Exception {
        Instant deadline = Instant.now().plus(LOCK_WAIT_TIMEOUT);
        try (Connection observer = fixture.connectAsApplication();
             PreparedStatement waiters = observer.prepareStatement("""
                 SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND wait_event_type = 'Lock'""")) {
            while (true) {
                try (ResultSet count = waiters.executeQuery()) {
                    count.next();
                    if (count.getInt(1) == expected) {
                        return;
                    }
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new AssertionError("Expected " + expected + " sessions waiting for the group lock");
                }
                Thread.sleep(20);
            }
        }
    }

    private static <T> T inOperation(IntegrationTestContext fixture,
            Function<OperationTestScope, T> work) {
        return new OperationTestHarness(fixture.context().getBean(ServiceScopeFactory.class)).inOperationScope(work);
    }

    private static OperationDispatcher dispatcher(IntegrationTestContext fixture) {
        return fixture.context().getBean(OperationDispatcher.class);
    }

    private static <T> List<T> query(IntegrationTestContext fixture, String sql, RowReader<T> reader)
            throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<T> values = new ArrayList<>();
            while (rows.next()) {
                values.add(reader.read(rows));
            }
            return values;
        }
    }

    private static GroupMetadata metadata(String name) {
        return new GroupMetadata(new GroupName(name), Optional.<GroupDescription>empty());
    }

    private static <T> T ok(Result<T, GroupFailure> result) {
        return switch (result) {
            case Result.Ok<T, GroupFailure>(T value) -> value;
            case Result.Err<T, GroupFailure>(GroupFailure failure) -> throw new AssertionError("expected success: " + failure);
        };
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }
}
