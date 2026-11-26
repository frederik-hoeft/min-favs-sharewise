package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.GroupConcurrencyFixture.LOCK_WAIT_TIMEOUT;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupConcurrencyFixture.lockGroup;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ALICE;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.BOB;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.create;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.dispatcher;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ok;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.query;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.replace;
import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.awaitLockWaiters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestScope;
import de.hsh.favs.sharewise.testsupport.postgres.BoundedStatementsConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
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

/** Durable group creation, metadata replacement and aggregate-lock synchronization. */
@Tag("e2e")
class GroupOperationsE2E extends E2ETestBase {

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
    void outsiderReplacementDoesNotWaitForAnExistingGroupLock() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            ok(create(fixture, BOB, "Other trip"));
            UUID missing = UUID.randomUUID();
            Result<GroupDetails, GroupFailure> denied = Result.err(new GroupFailure.GroupNotFound());
            try (ExecutorService callers = Executors.newSingleThreadExecutor();
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<GroupDetails, GroupFailure>> nonexistent =
                    callers.submit(() -> replace(fixture, BOB, missing, "Renamed", 0));
                assertEquals(denied, nonexistent.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                Future<Result<GroupDetails, GroupFailure>> existing =
                    callers.submit(() -> replace(fixture, BOB, group, "Renamed", 0));
                // Must complete while the holder still owns the root lock. The old predicate
                // would block here; the bounded get fails before releasing the holder.
                assertEquals(denied, existing.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                holder.commit();
            }
            assertEquals(List.of("Other trip|0", "Trip|0"), query(fixture,
                "SELECT name, version FROM groups.groups ORDER BY name", row -> row.getString(1) + "|" + row.getLong(2)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void concurrentReplacementsWithTheSameVersionSerializeOnTheGroupLock() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);

            List<Result<GroupDetails, GroupFailure>> results = new ArrayList<>();
            try (ExecutorService callers = Executors.newFixedThreadPool(2);
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<GroupDetails, GroupFailure>> alice =
                    callers.submit(() -> replace(fixture, ALICE, group, "Alice's trip", 0));
                Future<Result<GroupDetails, GroupFailure>> bob =
                    callers.submit(() -> replace(fixture, BOB, group, "Bob's trip", 0));
                awaitLockWaiters(fixture, holder, 2, LOCK_WAIT_TIMEOUT);
                holder.commit();
                results.add(alice.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                results.add(bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().filter(Result::isSuccess).count(), results::toString);
            assertEquals(1, results.stream().filter(new Result.Err<GroupDetails, GroupFailure>(
                new GroupFailure.VersionConflict())::equals).count(), results::toString);
            assertEquals(List.of(1L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void replacementWaitingForTheLockObservesAConcurrentlyCommittedDemotion() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            GroupTestFixture.addMember(fixture, group, BOB, GroupRole.ADMIN);

            Result<GroupDetails, GroupFailure> result;
            try (ExecutorService callers = Executors.newSingleThreadExecutor();
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<GroupDetails, GroupFailure>> replacement =
                    callers.submit(() -> replace(fixture, ALICE, group, "Renamed", 0));
                awaitLockWaiters(fixture, holder, 1, LOCK_WAIT_TIMEOUT);
                GroupTestFixture.changeRole(DSL.using(holder, SQLDialect.POSTGRES), group, ALICE, GroupRole.MEMBER);
                holder.commit();
                result = replacement.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }

            assertEquals(Result.err(new GroupFailure.AdministratorRequired()), result);
            assertEquals(List.of("Trip|0"), query(fixture,
                "SELECT name, version FROM groups.groups", row -> row.getString(1) + "|" + row.getLong(2)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void failedSynchronizationReleasesHolderBeforeJoiningWorkers() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            try (Connection pooled = fixture.dataSource().getConnection();
                 Statement statement = pooled.createStatement();
                 ResultSet setting = statement.executeQuery("SHOW statement_timeout")) {
                assertTrue(setting.next());
                assertEquals("30s", setting.getString(1));
            }
            AssertionError failure = assertThrows(AssertionError.class, () -> {
                try (ExecutorService callers = Executors.newSingleThreadExecutor();
                     Connection holder = lockGroup(fixture, group)) {
                    callers.submit(() -> replace(fixture, ALICE, group, "Renamed", 0));
                    awaitLockWaiters(fixture, holder, 1, LOCK_WAIT_TIMEOUT);
                    // Deliberately fail while the worker is waiting on the holder.
                    awaitLockWaiters(fixture, holder, 2, Duration.ofMillis(100));
                }
            });
            assertTrue(failure.getMessage().startsWith("Expected 2 sessions blocked"), failure::toString);
            // The worker must commit after holder cleanup, not time out waiting for the lock.
            assertEquals(List.of("Renamed|1"), query(fixture,
                "SELECT name, version FROM groups.groups WHERE id = '" + group + "'",
                row -> row.getString(1) + "|" + row.getLong(2)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    private static <T> T inOperation(IntegrationTestContext fixture,
            Function<OperationTestScope, T> work) {
        return new OperationTestHarness(fixture.context().getBean(ServiceScopeFactory.class)).inOperationScope(work);
    }

    private static GroupMetadata metadata(String name) {
        return new GroupMetadata(new GroupName(name), Optional.<GroupDescription>empty());
    }
}
