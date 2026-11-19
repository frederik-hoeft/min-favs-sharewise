package de.hsh.favs.sharewise.testsupport.integration;

import de.hsh.favs.sharewise.testsupport.operation.OperationTestScope;
import de.hsh.favs.sharewise.testsupport.transactions.PendingStateInspector;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackPhysicalTransactionOwner;
import de.hsh.favs.sharewise.testsupport.transactions.TransactionObservation;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Test-side access to one operation scope within the harness-owned physical transaction. */
public final class IntegrationTestScope {

    private final OperationTestScope operation;
    private final DataSource dataSource;
    private final RollbackPhysicalTransactionOwner witness;
    private boolean active = true;

    IntegrationTestScope(OperationTestScope operation, DataSource dataSource,
            RollbackPhysicalTransactionOwner witness) {
        this.operation = Objects.requireNonNull(operation);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.witness = Objects.requireNonNull(witness);
    }

    public <S> S resolve(Class<S> serviceType) {
        requireCurrentTransaction();
        return operation.resolve(serviceType);
    }

    public TransactionObservation observation() {
        return resolve(RollbackTransactions.class).observation();
    }

    /**
     * Inspect after the sole logical segment has completed; the original physical transaction
     * remains active, but expired application facades do not become usable again.
     */
    public <T> T inspect(Function<PendingStateInspector, T> assertion) {
        Objects.requireNonNull(assertion, "assertion");
        observation(); // Reject inspection before logical completion.
        requireCurrentTransaction();
        Inspector inspector = new Inspector();
        try {
            return assertion.apply(inspector);
        } finally {
            inspector.active = false;
        }
    }

    void close() {
        active = false;
    }

    private void requireCurrentTransaction() {
        if (!active) {
            throw new IllegalStateException("Rollback inspection requires its original active transaction");
        }
        witness.requireCurrent();
    }

    private final class Inspector implements PendingStateInspector {
        private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        private boolean active = true;

        private void requireActive() {
            if (!active) {
                throw new IllegalStateException("Pending-state inspector has expired");
            }
            requireCurrentTransaction();
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            requireActive();
            return jdbc.query(sql, rowMapper, arguments);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
            requireActive();
            return jdbc.queryForObject(sql, resultType, arguments);
        }
    }
}
