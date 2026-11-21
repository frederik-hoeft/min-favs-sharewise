package de.hsh.favs.sharewise.testsupport.integration;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
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
public final class SharedIntegrationContextExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(SharedIntegrationContextExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        shared(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == IntegrationTestContext.class;
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
        private final IntegrationTestContext fixture;

        private SharedFixture() {
            fixture = IntegrationTestContext.start(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> {
                    try (Connection migrator = database.connectAsMigrator();
                         Statement statement = migrator.createStatement()) {
                        // Probe tables live in a test-owned schema, never in a bounded context's
                        // schema, so context-level checks (for example generated-schema
                        // freshness) see only production tables.
                        statement.execute("create schema test_probes");
                        statement.execute("grant usage on schema test_probes to sharewise_app");
                        statement.execute("alter default privileges in schema test_probes "
                            + "grant select, insert, update, delete on tables to sharewise_app");
                        statement.execute("create table test_probes.p13c_rollback_probe ("
                            + "id integer primary key, label varchar(255) not null)");
                        statement.execute("create table test_probes.rollback_tx_probe ("
                            + "id integer primary key, label varchar(255) not null)");
                    }
                    IntegrationTestContext.seedCommitted(database, connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "insert into test_probes.p13c_rollback_probe (id,label) values (1,'baseline')")) {
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
