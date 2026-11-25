package de.hsh.favs.sharewise.groups.internal.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.PropertySource;

/** Synchronizes independent database transactions around a specific Group aggregate lock. */
final class GroupConcurrencyFixture {

    static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(15);

    private GroupConcurrencyFixture() {
    }

    /** Holds the group row lock on an independent connection until the caller commits it. */
    static Connection lockGroup(IntegrationTestContext fixture, UUID group) throws SQLException {
        Connection holder = fixture.connectAsApplication();
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM groups.groups WHERE id = ? FOR UPDATE")) {
                lock.setQueryTimeout(15);
                lock.setObject(1, group);
                try (ResultSet locked = lock.executeQuery()) {
                    assertTrue(locked.next(), "group to lock must exist");
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

    /** Counts only sessions blocked directly or transitively by this specific root-lock holder. */
    static void awaitLockWaiters(IntegrationTestContext fixture, Connection holder,
            int expected, Duration timeout) throws Exception {
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

    /** Applies a session timeout to real service-owned transactions without wrapping them. */
    @TestConfiguration(proxyBeanMethods = false)
    @PropertySource("classpath:group-concurrency-test.properties")
    static class BoundedStatements { }
}
