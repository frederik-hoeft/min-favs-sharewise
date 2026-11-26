package de.hsh.favs.sharewise.groups.internal.persistence;

import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * Holds a specific Group aggregate lock for concurrency E2Es. Lock-wait observation and
 * statement bounds come from the shared {@link PostgresLockWaits} and
 * {@link de.hsh.favs.sharewise.testsupport.postgres.BoundedStatementsConfiguration}.
 */
final class GroupConcurrencyFixture {

    static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(15);

    private GroupConcurrencyFixture() {
    }

    /** Holds the group row lock on an independent connection until the caller commits it. */
    static Connection lockGroup(IntegrationTestContext fixture, UUID group) throws SQLException {
        return PostgresLockWaits.holdLocks(fixture, "SELECT id FROM groups.groups WHERE id = ? FOR UPDATE", group);
    }
}
