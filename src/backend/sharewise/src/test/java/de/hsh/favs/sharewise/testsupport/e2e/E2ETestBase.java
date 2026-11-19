package de.hsh.favs.sharewise.testsupport.e2e;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.context.ConfigurableApplicationContext;

/** Per-test ownership of a disposable migrated database, production context, and committed writes. */
@Tag("e2e")
public abstract class E2ETestBase {

    private IntegrationTestContext fixture;

    /** Create a disposable database on demand and always close it, including on callback failure. */
    protected final void runTest(TestCallbacks.Action<IntegrationTestContext> test, Class<?>... configurations) {
        runPreparedTest(null, test, configurations);
    }

    /** Targeted schema setup for infrastructure tests; application tests normally use migrations. */
    protected final void runTestWithSchema(String ddl, TestCallbacks.Action<IntegrationTestContext> test,
            Class<?>... configurations) {
        Objects.requireNonNull(ddl, "ddl");
        runPreparedTest(ddl, test, configurations);
    }

    /** Resolve the real application service inside an operation scope; it owns its own transactions. */
    protected final <S> void runServiceTest(Class<S> serviceType,
            TestCallbacks.Action2<S, IntegrationTestContext> test, Class<?>... configurations) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(test, "test");
        runTest(context -> new OperationTestHarness(context.context().getBean(ServiceScopeFactory.class))
            .inOperationScope(scope -> {
                TestCallbacks.assertSucceeds("E2E service callback", () ->
                    test.accept(scope.resolve(serviceType), context));
                return null;
            }), configurations);
    }

    private void runPreparedTest(String ddl, TestCallbacks.Action<IntegrationTestContext> test,
            Class<?>... configurations) {
        Objects.requireNonNull(test, "test");
        Objects.requireNonNull(configurations, "configurations");
        TestCallbacks.assertSucceeds("E2E test", () -> {
            boolean ownsFixture = fixture == null;
            if (ownsFixture) {
                if (ddl == null) {
                    start(configurations);
                } else {
                    startWithSchema(ddl, configurations);
                }
            } else if (ddl != null || configurations.length != 0) {
                throw new IllegalStateException("Fixture already initialized; configure schema and sources at startup");
            }
            Throwable callbackFailure = null;
            try {
                test.accept(fixture());
            } catch (Exception | Error failure) {
                callbackFailure = failure;
                throw failure;
            } finally {
                // Legacy @BeforeEach fixtures remain owned by @AfterEach; HOF-owned fixtures
                // close here so teardown failure is included in the assertion boundary.
                if (ownsFixture) {
                    try {
                        disposeDatabase();
                    } catch (RuntimeException | Error closeFailure) {
                        if (callbackFailure != null) {
                            callbackFailure.addSuppressed(closeFailure);
                        } else {
                            throw closeFailure;
                        }
                    }
                }
            }
        });
    }

    protected final void start(Class<?>... configurations) {
        startWithSchema(new String[0], configurations);
    }

    protected final void startWithSchema(String statement, Class<?>... configurations) {
        startWithSchema(new String[]{statement}, configurations);
    }

    private void startWithSchema(String[] schemaStatements, Class<?>... configurations) {
        if (fixture != null) {
            throw new IllegalStateException("E2E fixture already initialized");
        }
        Class<?>[] applicationSources = new Class<?>[configurations.length + 1];
        applicationSources[0] = ShareWiseApplication.class;
        System.arraycopy(configurations, 0, applicationSources, 1, configurations.length);
        fixture = IntegrationTestContext.start(
            PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> {
                if (schemaStatements.length != 0) {
                    try (Connection migrator = database.connectAsMigrator();
                         Statement statement = migrator.createStatement()) {
                        for (String ddl : schemaStatements) {
                            statement.execute(ddl);
                        }
                    }
                }
            }, applicationSources);
    }

    protected final ConfigurableApplicationContext context() {
        if (fixture == null) {
            throw new IllegalStateException("E2E fixture not initialized");
        }
        return fixture.context();
    }

    protected final IntegrationTestContext fixture() {
        if (fixture == null) {
            throw new IllegalStateException("E2E fixture not initialized");
        }
        return fixture;
    }

    @AfterEach
    protected final void disposeDatabase() {
        if (fixture != null) {
            try {
                fixture.close();
            } finally {
                fixture = null;
            }
        }
    }
}
