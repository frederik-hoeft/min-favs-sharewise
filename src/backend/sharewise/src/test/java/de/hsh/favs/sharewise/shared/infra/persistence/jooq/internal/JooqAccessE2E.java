package de.hsh.favs.sharewise.shared.infra.persistence.jooq.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresE2ETestCase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real-commit jOOQ and transaction lease coverage against an independently migrated database. */
@org.junit.jupiter.api.Tag("e2e")
class JooqAccessE2E extends PostgresE2ETestCase {

    OperationDispatcher dispatcher;
    JdbcTemplate jdbc;

    @BeforeEach
    void resetTable() {
        startWithSchema("create table groups.tx_access_probe (id integer primary key)",
            ProductionEntityScanConfiguration.class, Fixtures.class);
        dispatcher = context().getBean(OperationDispatcher.class);
        jdbc = context().getBean(JdbcTemplate.class);
    }

    @Test
    void acceptsSqlOnlyWithinItsOwningSegmentAndHonorsCommitAndRollback() {
        dispatcher.dispatch(Probe.class, probe -> {
            assertThrows(IllegalStateException.class,
                () -> probe.jooq().dsl().fetchOne("select 1"));

            probe.transactions().execute(() -> {
                int rows = probe.jooq().dsl().execute("insert into groups.tx_access_probe (id) values (1)");
                assertEquals(1, rows);
                return TransactionOutcome.commit(null);
            });
            probe.transactions().execute(() -> {
                probe.jooq().dsl().execute("insert into groups.tx_access_probe (id) values (2)");
                return TransactionOutcome.rollback(null);
            });
            return null;
        });
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("select count(*) from groups.tx_access_probe", Integer.class));
    }

    @Test
    void derivedQueriesFromAnEarlierSegmentCannotExecuteInLaterSegments() {
        dispatcher.dispatch(Probe.class, probe -> {
            AtomicReference<DSLContext> escapedDsl = new AtomicReference<>();
            AtomicReference<ResultQuery<Record>> escapedRead = new AtomicReference<>();
            AtomicReference<Query> escapedWrite = new AtomicReference<>();
            probe.transactions().execute(() -> {
                escapedDsl.set(probe.jooq().dsl());
                escapedRead.set(probe.jooq().dsl().resultQuery("select id from groups.tx_access_probe"));
                escapedWrite.set(probe.jooq().dsl().query("insert into groups.tx_access_probe (id) values (99)"));
                assertEquals(0, escapedRead.get().fetch().size());
                return TransactionOutcome.commit(null);
            });
            assertRejectedBySegmentLease(() -> escapedDsl.get().fetchOne("select 1"));
            assertRejectedBySegmentLease(() -> escapedRead.get().fetch());
            assertRejectedBySegmentLease(() -> escapedWrite.get().execute());

            probe.transactions().execute(() -> {
                assertRejectedBySegmentLease(() -> escapedDsl.get().fetchOne("select 1"));
                assertRejectedBySegmentLease(() -> escapedRead.get().fetch());
                assertRejectedBySegmentLease(() -> escapedWrite.get().execute());
                Integer result = probe.jooq().dsl().fetchOne("select 1").get(0, Integer.class);
                assertEquals(Integer.valueOf(1), result);
                return TransactionOutcome.commit(null);
            });
            return null;
        });
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("select count(*) from groups.tx_access_probe", Integer.class));
    }

    private static void assertRejectedBySegmentLease(Executable executable) {
        RuntimeException failure = assertThrows(RuntimeException.class, executable);
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof IllegalStateException,
            "Expected segment lease rejection, got " + failure);
    }

    @Test
    void jooqTransactionEntryPointsCannotBypassOwningSegment() {
        dispatcher.dispatch(Probe.class, probe -> {
            AtomicReference<DSLContext> escaped = new AtomicReference<>();
            probe.transactions().execute(() -> {
                DSLContext db = probe.jooq().dsl();
                escaped.set(db);
                assertJooqTransactionEntryRejected(db);
                return TransactionOutcome.commit(null);
            });
            // A stale DSLContext must not be able to open an unrelated Spring/JDBC
            // transaction even if its callback performs no guarded jOOQ SQL.
            assertJooqTransactionEntryRejected(escaped.get());
            probe.transactions().execute(() -> {
                assertJooqTransactionEntryRejected(escaped.get());
                return TransactionOutcome.commit(null);
            });
            return null;
        });
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
            "select count(*) from groups.tx_access_probe", Integer.class));
    }

    private static void assertJooqTransactionEntryRejected(DSLContext db) {
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(IllegalStateException.class,
            () -> db.transaction(configuration -> invoked.set(true)));
        assertThrows(IllegalStateException.class,
            () -> db.transactionResult(configuration -> {
            invoked.set(true);
            return "unexpected";
        }));
        assertFalse(invoked.get(), "A rejected transaction must not invoke its callback");
    }

    @Test
    void jooqAccessCannotBeUsedThroughAnotherScopeOrThread() {
        JooqAccess escaped = dispatcher.dispatch(Probe.class, Probe::jooq);
        assertThrows(IllegalStateException.class, () -> escaped.dsl());
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                assertThrows(IllegalStateException.class, () -> escaped.dsl());
                FutureTask<Void> wrongThread = new FutureTask<>(() -> {
                    assertThrows(IllegalStateException.class, () -> probe.jooq().dsl());
                    return null;
                });
                Thread.ofVirtual().start(wrongThread);
                try {
                    wrongThread.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    record Probe(Transactions transactions, JooqAccess jooq) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Scope("operation")
        Probe probe(Transactions transactions, JooqAccess jooq) {
            return new Probe(transactions, jooq);
        }
    }
}
