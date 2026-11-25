package de.hsh.favs.sharewise.testsupport.integration;

import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Shared PostgreSQL application context, but an independent rollback-only physical transaction
 * and operation DI scope for every invocation. Test methods may run concurrently because
 * transaction bindings and operation scopes are thread-local; tests must use disjoint write
 * keys and must not mutate the shared Spring context or its singleton state.
 * A service under test owns its logical segment; the helper must not open a second one.
 */
@Tag("integration")
@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(SharedIntegrationContextExtension.class)
public abstract class IntegrationTestBase {

    private IntegrationTestContext fixture;

    @BeforeEach
    protected final void bindIntegrationContext(IntegrationTestContext context) {
        fixture = Objects.requireNonNull(context);
    }

    protected final IntegrationTestContext fixture() {
        return Objects.requireNonNull(fixture, "Integration context has not been initialized");
    }

    protected final IntegrationTestRunner runner() {
        return new IntegrationTestRunner(fixture());
    }

    /** Physical transaction + operation scope, with mandatory physical rollback. */
    protected final void runIntegrationTest(TestCallbacks.Action<IntegrationTestScope> test) {
        Objects.requireNonNull(test, "test");
        TestCallbacks.assertSucceeds("Integration test", () -> runner().inRollbackOperation(scope -> {
            TestCallbacks.assertSucceeds("Integration test callback", () -> test.accept(scope));
            return null;
        }));
    }

    /** Resolve a real application service in the test operation scope, without starting its logical segment. */
    protected final <S> void runServiceTest(Class<S> serviceType,
            TestCallbacks.Action2<S, IntegrationTestScope> test) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(test, "test");
        runIntegrationTest(scope -> test.accept(scope.resolve(serviceType), scope));
    }

    /** For infrastructure tests that explicitly exercise the single logical transaction segment. */
    protected final void runTransactionTest(TestCallbacks.Action2<RollbackTransactions, IntegrationTestScope> test) {
        Objects.requireNonNull(test, "test");
        runServiceTest(RollbackTransactions.class, test);
    }
}
