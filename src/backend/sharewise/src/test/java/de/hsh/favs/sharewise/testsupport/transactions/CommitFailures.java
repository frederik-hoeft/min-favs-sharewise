package de.hsh.favs.sharewise.testsupport.transactions;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/** Fault injection at the production transaction manager's physical commit for E2E tests. */
public final class CommitFailures {

    private CommitFailures() {
    }

    /**
     * Arms a one-shot failure just before the next physical commit started on the calling thread,
     * after the segment's work and any publication have happened. Background registry and listener
     * transactions run on other threads and cannot consume it. Returns the exception that commit
     * will throw.
     */
    public static IllegalStateException failNextCommit(IntegrationTestContext fixture) {
        Objects.requireNonNull(fixture, "fixture");
        JpaTransactionManager manager = assertInstanceOf(JpaTransactionManager.class,
            fixture.context().getBean(PlatformTransactionManager.class));
        IllegalStateException failure = new IllegalStateException("Simulated failure before physical commit");
        List<TransactionExecutionListener> listeners = new ArrayList<>(manager.getTransactionExecutionListeners());
        Thread armingThread = Thread.currentThread();
        listeners.add(new TransactionExecutionListener() {
            private volatile boolean armed = true;

            @Override
            public void beforeCommit(TransactionExecution transaction) {
                if (armed && transaction.isNewTransaction() && Thread.currentThread() == armingThread) {
                    armed = false;
                    throw failure;
                }
            }
        });
        manager.setTransactionExecutionListeners(listeners);
        return failure;
    }
}
