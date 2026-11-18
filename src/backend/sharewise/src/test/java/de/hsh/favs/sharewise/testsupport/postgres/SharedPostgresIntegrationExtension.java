package de.hsh.favs.sharewise.testsupport.postgres;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.testsupport.postgres.RollbackProbeEntityScanConfiguration;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackProbeTestConfiguration;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactionTestConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * One migrated PostgreSQL database and one application context per JUnit launcher execution.
 * JUnit's root store owns cleanup. Every test operation gets its own Spring physical transaction;
 * nothing mutable or transaction-bound is shared between test invocations.
 */
public final class SharedPostgresIntegrationExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(SharedPostgresIntegrationExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        shared(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == PostgresWorkflowFixture.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return shared(context).fixture;
    }

    private static SharedFixture shared(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(
            SharedFixture.class, ignored -> new SharedFixture(), SharedFixture.class);
    }

    private static final class SharedFixture implements AutoCloseable {
        private final PostgresWorkflowFixture fixture;

        private SharedFixture() {
            fixture = PostgresWorkflowFixture.start(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> {
                    try (Connection migrator = database.connectAsMigrator();
                         Statement statement = migrator.createStatement()) {
                        statement.execute("create table groups.p13c_rollback_probe ("
                            + "id integer primary key, label varchar(255) not null)");
                        statement.execute("create table groups.rollback_tx_probe ("
                            + "id integer primary key, label varchar(255) not null)");
                    }
                    PostgresWorkflowFixture.seedCommitted(database, connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "insert into groups.p13c_rollback_probe (id,label) values (1,'baseline')")) {
                            statement.executeUpdate();
                        }
                    });
                }, ShareWiseApplication.class, RollbackProbeEntityScanConfiguration.class,
                RollbackTransactionTestConfiguration.class, RollbackProbeTestConfiguration.class);
        }

        @Override
        public void close() {
            fixture.close();
        }
    }
}
