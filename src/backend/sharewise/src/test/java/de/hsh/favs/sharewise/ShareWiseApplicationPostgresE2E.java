package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the production application context against PostgreSQL (TC-4): Flyway migrates a fresh
 * database as the migration role, Hibernate validates its mappings ({@code ddl-auto=validate}) and the
 * runtime datasource connects as the application role.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@org.junit.jupiter.api.Tag("e2e")
class ShareWiseApplicationPostgresE2E {

    private static PostgresTestDatabase database;

    @Autowired
    DataSource dataSource;

    @Autowired
    ServiceScopeFactory scopeFactory;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        database = PostgresTestDatabase.createBootstrapped();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresTestDatabase.APPLICATION_USER);
        registry.add("spring.datasource.password", () -> PostgresTestDatabase.APPLICATION_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.user", () -> PostgresTestDatabase.MIGRATOR_USER);
        registry.add("spring.flyway.password", () -> PostgresTestDatabase.MIGRATOR_PASSWORD);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * The test classpath also contains purpose-built probe entities that must not be registered
     * in the ordinary production context. Production mappings are selected explicitly.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProductionEntityScanConfiguration.class)
    static class Fixtures {

        /**
         * Drops the test database when the context closes ({@code @DirtiesContext}), after the connection
         * pool and the EntityManagerFactory: the datasource depends on this bean, so it is destroyed first.
         */
        @Bean(destroyMethod = "close")
        PostgresTestDatabase postgresTestDatabase() {
            return database;
        }

        @Bean
        static BeanFactoryPostProcessor dropDatabaseAfterDataSource() {
            return beanFactory -> {
                if (!beanFactory.containsBeanDefinition("dataSource")) {
                    return;
                }
                BeanDefinition dataSource = beanFactory.getBeanDefinition("dataSource");
                String[] dependsOn = dataSource.getDependsOn() == null ? new String[0] : dataSource.getDependsOn();
                dataSource.setDependsOn(Stream.concat(Arrays.stream(dependsOn), Stream.of("postgresTestDatabase"))
                    .toArray(String[]::new));
            };
        }
    }

    @Test
    void flywayAppliedTheBaselineAsTheMigrationRole() throws SQLException {
        try (Connection admin = database.connectAsAdmin();
             Statement statement = admin.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT installed_by, success FROM flyway.flyway_schema_history WHERE version IS NOT NULL")) {
            assertTrue(rows.next(), "no versioned migration was applied");
            do {
                assertEquals(PostgresTestDatabase.MIGRATOR_USER, rows.getString(1));
                assertTrue(rows.getBoolean(2));
            } while (rows.next());
        }
    }

    @Test
    void runtimeConnectionUsesTheApplicationRole() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT current_user")) {
            rows.next();
            assertEquals(PostgresTestDatabase.APPLICATION_USER, rows.getString(1));
        }
    }

    @Test
    void transactionsAndJooqRunAgainstPostgres() {
        AtomicReference<String> version = new AtomicReference<>();
        try (ServiceScope scope = scopeFactory.createScope()) {
            scope.run(provider -> {
                Transactions transactions = provider.getRequiredService(Transactions.class);
                JooqAccess jooq = provider.getRequiredService(JooqAccess.class);
                transactions.execute(() -> {
                    version.set(String.valueOf(jooq.dsl().fetchValue("SELECT version()")));
                    return TransactionOutcome.commit(null);
                });
                return null;
            });
        }
        assertTrue(version.get().startsWith("PostgreSQL 18"), version.get());
    }
}
