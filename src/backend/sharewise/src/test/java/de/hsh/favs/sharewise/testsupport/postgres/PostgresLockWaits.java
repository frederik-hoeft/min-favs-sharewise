package de.hsh.favs.sharewise.testsupport.postgres;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jooq.DSLContext;

/**
 * Deterministic coordination of concurrent E2E transactions through observable PostgreSQL lock
 * waits instead of sleeps. Every wait is bounded, and every query reads live lock-manager state
 * ({@code pg_blocking_pids}) from a fresh statement-level snapshot.
 */
public final class PostgresLockWaits {

    /** Upper bound for a single coordination step or contender completion. */
    public static final Duration WAIT = Duration.ofSeconds(10);

    private PostgresLockWaits() {
    }

    /**
     * Opens an independent application-role transaction that executes {@code sql} and keeps its
     * locks until the caller commits, rolls back or closes the returned connection. The statement
     * must return or change at least one row, so a missing fixture cannot silently lock nothing.
     */
    public static Connection holdLocks(IntegrationTestContext fixture, String sql, Object... parameters)
            throws SQLException {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(sql, "sql");
        Connection holder = fixture.connectAsApplication();
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(sql)) {
                lock.setQueryTimeout(15);
                for (int index = 0; index < parameters.length; index++) {
                    lock.setObject(index + 1, parameters[index]);
                }
                if (lock.execute()) {
                    try (ResultSet locked = lock.getResultSet()) {
                        assertTrue(locked.next(), "locking query must lock at least one row");
                    }
                } else {
                    assertTrue(lock.getUpdateCount() > 0, "locking statement must change at least one row");
                }
            }
            return holder;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                holder.close();
            } catch (SQLException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Waits until exactly {@code expected} sessions of the fixture database are blocked directly or
     * transitively by {@code holder}, for example queued behind an earlier waiter's tuple lock.
     * Observes from a separate autocommit connection, so contenders need not expose their PIDs.
     */
    public static void awaitLockWaiters(IntegrationTestContext fixture, Connection holder, int expected,
            Duration timeout) throws SQLException, InterruptedException {
        int holderPid;
        try (Statement statement = holder.createStatement()) {
            statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertTrue(row.next());
                holderPid = row.getInt(1);
            }
        }
        long start = System.nanoTime();
        try (Connection observer = fixture.connectAsApplication();
             PreparedStatement waiters = observer.prepareStatement("""
                 WITH RECURSIVE blocked(pid) AS (
                     SELECT ?::integer
                     UNION
                     SELECT activity.pid FROM pg_stat_activity activity
                     JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                     WHERE activity.datname = current_database()
                 )
                 SELECT count(*) - 1 FROM blocked
                 """)) {
            waiters.setQueryTimeout(5);
            waiters.setInt(1, holderPid);
            while (true) {
                try (ResultSet count = waiters.executeQuery()) {
                    assertTrue(count.next());
                    if (count.getInt(1) == expected) {
                        return;
                    }
                }
                if (System.nanoTime() - start >= timeout.toNanos()) {
                    throw new AssertionError("Expected " + expected + " sessions blocked by backend " + holderPid);
                }
                Thread.sleep(20);
            }
        }
    }

    public static int backendPid(DSLContext db) {
        return db.fetchSingle("select pg_backend_pid()").get(0, Integer.class);
    }

    /**
     * Waits until the contender whose backend PID it reports is blocked by {@code ownerDb}'s
     * session, failing if the contender completes first. Runs on the owner's own connection.
     */
    public static void awaitBlockedBy(DSLContext ownerDb, Future<Integer> contenderPid, Future<?> contender) {
        int owner = backendPid(ownerDb);
        int waiting = await(contenderPid);
        assertNotEquals(owner, waiting, "Operations must use independent connections");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            assertFalse(contender.isDone(), "Contender completed while the owner still held the lock");
            // pg_blocking_pids reads live lock-manager state rather than a cached statistics snapshot.
            if (Boolean.TRUE.equals(ownerDb.fetchSingle("select ? = any(pg_blocking_pids(?))", owner, waiting)
                    .get(0, Boolean.class))) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while observing PostgreSQL lock wait", failure);
            }
        }
        throw new AssertionError("PostgreSQL did not report the expected blocking relationship");
    }

    /** The contender's result within {@link #WAIT}; its failure becomes the assertion's cause. */
    public static <T> T await(Future<T> result) {
        try {
            return result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for a concurrent transaction", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("Concurrent transaction did not complete successfully", failure);
        }
    }
}
