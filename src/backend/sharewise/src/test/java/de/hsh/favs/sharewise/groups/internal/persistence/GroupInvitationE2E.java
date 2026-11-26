package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.GroupConcurrencyFixture.LOCK_WAIT_TIMEOUT;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupConcurrencyFixture.lockGroup;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ALICE;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.BOB;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.change;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.create;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.invitation;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.ok;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.query;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.redeem;
import static de.hsh.favs.sharewise.groups.internal.persistence.GroupE2ESupport.workspace;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.awaitLockWaiters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemption;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.BoundedStatementsConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Durable invitation redemption, usage accounting and concurrent revocation/expiry. */
@Tag("e2e")
class GroupInvitationE2E extends E2ETestBase {

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
            try (ExecutorService callers = Executors.newFixedThreadPool(2);
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<InvitationRedemption, GroupFailure>> bob = callers.submit(() -> redeem(fixture, BOB, link));
                Future<Result<InvitationRedemption, GroupFailure>> other = callers.submit(() -> redeem(fixture, carol, link));
                awaitLockWaiters(fixture, holder, 2, LOCK_WAIT_TIMEOUT);
                holder.commit();
                List<Result<InvitationRedemption, GroupFailure>> results = List.of(
                    bob.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS), other.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Result::isSuccess).count());
                assertTrue(results.contains(Result.err(new GroupFailure.InvitationUnavailable())));
            }
            assertEquals(List.of(1), query(fixture, "SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
            assertEquals(List.of(2L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
            assertEquals(List.of(0L), query(fixture, "SELECT version FROM groups.groups", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void invalidCredentialsDoNotWaitForTheGroupRootLock() {
        runTest(fixture -> {
            UUID carol = UUID.randomUUID();
            GroupTestFixture.provision(fixture, ALICE, BOB, carol);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation active = invitation(fixture, group, null, null);
            CreatedInvitation exhausted = invitation(fixture, group, null, 1);
            CreatedInvitation expired = invitation(fixture, group, null, null);
            ok(redeem(fixture, carol, exhausted));
            // Seed historical expiry as a migrator; no production operation mutates these fields.
            try (Connection connection = fixture.database().connectAsMigrator();
                 PreparedStatement seed = connection.prepareStatement("""
                     UPDATE groups.invitations
                     SET created_at = clock_timestamp() - interval '2 minutes',
                         expires_at = clock_timestamp() - interval '1 minute'
                     WHERE id = ?""")) {
                seed.setQueryTimeout(5);
                seed.setObject(1, expired.invitation().id());
                assertEquals(1, seed.executeUpdate());
            }
            String forged = fixture.context().getBean(ApplicationTokenGenerator.class)
                .generate(active.invitation().id()).value();
            try (ExecutorService callers = Executors.newSingleThreadExecutor();
                 Connection holder = lockGroup(fixture, group)) {
                for (String token : List.of(forged, expired.token().value(), exhausted.token().value())) {
                    Future<Result<InvitationRedemption, GroupFailure>> result = callers.submit(() -> redeem(fixture, BOB, token));
                    assertEquals(Result.err(new GroupFailure.InvitationUnavailable()),
                        result.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                }
                holder.commit();
            }
            assertEquals(List.of(2L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
            assertEquals(List.of(0, 0, 1), query(fixture, "SELECT use_count FROM groups.invitations ORDER BY use_count", row -> row.getInt(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void redemptionRereadsInvitationAfterRevocationCommits() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            CreatedInvitation link = invitation(fixture, group, null, null);
            try (ExecutorService callers = Executors.newSingleThreadExecutor();
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<InvitationRedemption, GroupFailure>> redemption = callers.submit(() -> redeem(fixture, BOB, link));
                awaitLockWaiters(fixture, holder, 1, LOCK_WAIT_TIMEOUT);
                try (PreparedStatement revoke = holder.prepareStatement("DELETE FROM groups.invitations WHERE id = ?")) {
                    revoke.setQueryTimeout(5);
                    revoke.setObject(1, link.invitation().id());
                    assertEquals(1, revoke.executeUpdate());
                }
                holder.commit();
                assertEquals(Result.err(new GroupFailure.InvitationUnavailable()), redemption.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
    }

    @Test
    void invitationExpiresWhileRedemptionWaitsForGroupLock() {
        runTest(fixture -> {
            GroupTestFixture.provision(fixture, ALICE, BOB);
            UUID group = ok(create(fixture, ALICE, "Trip")).id();
            // Obtain a deadline using the database clock, not the test JVM clock.
            Instant deadline = query(fixture, "SELECT clock_timestamp() + interval '15 seconds'",
                row -> row.getObject(1, OffsetDateTime.class).toInstant()).getFirst();
            CreatedInvitation link = invitation(fixture, group, deadline, null);
            try (ExecutorService callers = Executors.newSingleThreadExecutor();
                 Connection holder = lockGroup(fixture, group)) {
                Future<Result<InvitationRedemption, GroupFailure>> redemption = callers.submit(() -> redeem(fixture, BOB, link));
                awaitLockWaiters(fixture, holder, 1, LOCK_WAIT_TIMEOUT);
                // Check transaction and lock-statement start times, not when the observer
                // happens to be scheduled after detecting the waiter.
                try (PreparedStatement started = holder.prepareStatement("""
                        SELECT xact_start < ?, query_start < ? FROM pg_stat_activity
                        WHERE datname = current_database()
                          AND pg_backend_pid() = ANY(pg_blocking_pids(pid))""")) {
                    started.setQueryTimeout(5);
                    started.setObject(1, deadline.atOffset(ZoneOffset.UTC));
                    started.setObject(2, deadline.atOffset(ZoneOffset.UTC));
                    try (ResultSet rows = started.executeQuery()) {
                        assertTrue(rows.next() && rows.getBoolean(1), "redemption must begin before expiry");
                        assertTrue(rows.getBoolean(2), "redemption must start the root-lock statement before expiry");
                        assertTrue(!rows.next(), "expected exactly one redemption blocked by this holder");
                    }
                }
                // The lifetime is 15s; the worker statement timeout is 30s. Allow the
                // holder sleep extra scheduling margin while staying below that timeout.
                try (PreparedStatement wait = holder.prepareStatement("SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - clock_timestamp()))))")) {
                    wait.setQueryTimeout(Math.toIntExact(LOCK_WAIT_TIMEOUT.plusSeconds(5).toSeconds()));
                    wait.setObject(1, deadline.atOffset(ZoneOffset.UTC));
                    wait.execute();
                }
                holder.commit();
                assertEquals(Result.err(new GroupFailure.InvitationUnavailable()), redemption.get(LOCK_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            assertEquals(List.of(0), query(fixture, "SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
            assertEquals(List.of(1L), query(fixture, "SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
        }, ProductionEntityScanConfiguration.class, IdentityTestConfiguration.class, BoundedStatementsConfiguration.class);
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
}
