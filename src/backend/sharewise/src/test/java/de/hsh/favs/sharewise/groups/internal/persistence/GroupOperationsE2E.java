package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.GroupCreationPort;
import de.hsh.favs.sharewise.groups.internal.application.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.GroupMetadataService;
import de.hsh.favs.sharewise.groups.internal.application.GroupMembershipService;
import de.hsh.favs.sharewise.groups.internal.application.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.application.MembershipChangeCommand;
import de.hsh.favs.sharewise.groups.internal.application.MembershipChangeStatus;
import de.hsh.favs.sharewise.groups.internal.application.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.ReplaceGroupMetadataCommand;
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
    void transferAndRejoinPreserveMembershipIdentityAndMetadataConcurrency() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.MEMBER);
            UUID aliceId = workspace(fixture, ALICE, group).callerMembershipId();
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            ok(change(fixture, ALICE, group, bobId, new MembershipChange.TransferAdministration()));
            assertEquals(GroupRole.MEMBER, workspace(fixture, ALICE, group).group().callerRole());
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
    void failedSecondTransferWriteRollsBackTheFirst() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.MEMBER);
            UUID bobId = workspace(fixture, BOB, group).callerMembershipId();
            // Force a database rejection of the initiating administrator's demotion, not the promotion.
            try (Connection migrator = fixture.database().connectAsMigrator();
                 Statement ddl = migrator.createStatement()) {
                ddl.execute("ALTER TABLE groups.group_members ADD CONSTRAINT test_alice_admin CHECK "
                    + "(user_id <> '" + ALICE + "'::uuid OR role = 'admin')");
            }
            assertThrows(RuntimeException.class, () -> change(fixture, ALICE, group, bobId,
                new MembershipChange.TransferAdministration()));
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
