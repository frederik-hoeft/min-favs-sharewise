# P1.3c - Integration-test execution and isolation

**Status:** Draft specification for review; no implementation or approval is implied. **Parent backlog item:** [P1.3](../backlog.md#phase-1---modular-application-and-persistence-foundation). This task depends on the [operation-scope architecture](../../../stack/dependency-injection.md), [P1.3 production transaction specification](../p1.3-transactions/specification.md), [persistence architecture](../../../stack/persistence.md), and [messaging architecture](../../../stack/messaging.md). The [development workflow](../../../development/ai-development-workflow.md) defines the specification, planning and review gates.

## Goal and architectural boundary

Integration tests exercise real operation-scoped Spring dependency injection and, where required, the actual PostgreSQL transaction and event-publication infrastructure, while remaining deterministic, isolated and safe to execute concurrently when their fixtures are independent. Test code can resolve and inspect collaborating services **inside the same application-operation DI scope** as the subject under test. Transaction tests additionally distinguish an application's **requested disposition** from a **physically completed database commit or rollback**.

This is a test-infrastructure companion to P1.3a (operation-scoped DI) and P1.3b (production transactions), not a redesign of either. The production `OperationDispatcher`, operation-scope implementation, `Transactions` contract and `TransactionOutcome<T>` remain authoritative. The test harness shares the operation-scope machinery and may substitute a test-only implementation of `Transactions` through Spring's test bean configuration. Any necessary hook for controlled scope activation or active-segment resource access is a narrow extension point of the existing infrastructure, not a second container, a new ambient transaction registry, or a different production lifecycle.

P1.2 supplies the PostgreSQL runtime, migrations and baseline persistence configuration; messaging infrastructure is introduced by its owning feature tasks. P1.3c supplies repeatable **test execution and isolation**, not a second PostgreSQL bootstrap, finance business logic, a broker, a publication registry or an application-wide mocking framework. These dependencies may be developed and verified incrementally without claiming unimplemented messaging guarantees.

## Test categories and guarantees

The categories describe **execution contracts**, not necessarily separate Maven modules or inheritance trees. Maven/JUnit test selection, annotation names and fixture package locations are implementation choices.

| Category | Dependencies and isolation | What it can establish |
| --- | --- | --- |
| **Unit tests** | Plain JUnit with directly constructed services and fake/mock ports; no Spring context or PostgreSQL | Domain logic, typed business results and application orchestration; parallel by default |
| **Operation integration tests** | Real Spring operation scope, real persistence adapters, and PostgreSQL; one **test-owned physical transaction** rolled back at the end | DI scope identity/lifetime, normal single-transaction application operations, uncommitted database state and recorded application transaction decisions |
| **Workflow integration tests** | Production dispatcher and `Transactions` implementation, **real physical commits**, isolated disposable PostgreSQL database/fixture | Multiple sequential transactions, committed-state visibility, persistence concurrency, Spring Modulith publication/delivery, idempotency, recovery and commit-time behavior |
| **Cross-service E2E/smoke tests** | Future opt-in suite with isolated deployments and external infrastructure | Cross-process transport, real broker behavior and deployment wiring; not required for the initial modular monolith |

An **operation** is a business entry operation whose behavior fits within one `Transactions.execute(...)` segment. It can compose arbitrarily many participating services and persistence ports inside that segment. A **workflow** depends on multiple independently finalized transaction segments or on asynchronous/externally observable commit boundaries. This distinction determines the **test mode** and does not require separate mandatory production service interfaces or limit an operation DI scope to one physical transaction in production.

Operation integration tests do **not** establish physical commit, physical rollback, after-commit event delivery, cross-transaction visibility, savepoint or recovery guarantees. Those belong to workflow/real-commit tests even when the tested application method happens to execute only one segment.

## Shared operation-scope test harness

A reusable **operation test harness** exposes the real application's scope creation, Spring bean resolution and destruction semantics to test code. It uses the **same underlying Spring `Scope` and operation context** as `OperationDispatcher`. Tests can resolve the entry service and other scoped collaborators, invoke an operation, inspect scoped state, and make assertions before the scope closes. Scope resolution is intentionally test-facing; production application services continue to use constructor injection, not a general service locator.

The conceptual test-facing API is illustrative, with final names chosen during implementation:

```java
interface OperationTestHarness {
    <T> T inRollbackOperation(Function<OperationTestScope, T> test);
    <T> T inOperationScope(Function<OperationTestScope, T> test);
}

interface OperationTestScope {
    <S> S resolve(Class<S> serviceType);
    TransactionObservation transactionObservation();
    // Test-only database inspection capability, when a test transaction is active.
    <T> T inspect(Function<TestDatabaseInspector, T> assertion);
}
```

`inOperationScope(...)` provides **only DI-scoped execution**; it does not implicitly begin a physical transaction and is useful for operation-local state and lifecycle tests. `inRollbackOperation(...)` additionally owns the enclosing PostgreSQL transaction and selects the rollback-test `Transactions` adapter. The standard dispatcher may still be exercised independently to verify real inbound dispatch; the test harness is an additional entry point to the same scope implementation, not a replacement for it.

The harness establishes a scope and, where requested, the test transaction **before** resolving any operation-scoped bean that requires those capabilities. It closes/invalidates the scope and releases any test-owned resources in `finally`-equivalent cleanup paths. The test's physical rollback completes before the enclosing harness reports successful teardown. A test may retain an object reference for a negative lifetime assertion, but cannot extend its validity by retaining it.

### Inspecting pending state

A normal production persistence adapter must reject SQL outside an active transaction segment, including after the business callback returns. The test harness must not weaken that guard globally just to allow post-operation assertions. Instead, the rollback mode provides a **test-only inspection capability** using the still-active, test-owned physical transaction and its Spring-bound connection. It can query uncommitted rows after the application has returned its logical decision. The inspection capability is absent from production injection contracts and cannot silently start a new physical transaction or leak its connection.

For other operation-scoped state, tests resolve and inspect the relevant scoped bean directly. An inspection read is **not** a second `Transactions.execute(...)` invocation. If a normal application query service starts a separate transaction, invoking it after the tested operation is a workflow and is excluded from rollback mode. Ordinary adapter methods and business-oriented port interfaces remain unchanged.

## Rollback-isolated operation integration tests

The harness starts **one physical PostgreSQL transaction for the test execution**, then activates an application DI scope and resolves its dependencies using the rollback-test `Transactions` implementation. The test adapter runs a single application `execute(...)` callback against that already active physical transaction, records the returned `TransactionOutcome<T>` or thrown exception, and returns the same application-level result/exception behavior where possible. It **does not physically commit or roll back** when that callback finishes. The enclosing test harness performs the physical rollback after assertions, including on failure.

The observation captures, as applicable, the requested **COMMIT / ROLLBACK / ABSTAIN**, application result, whether rollback-only was requested, callback exception, and logical completion status. It does not claim that a physical transaction finalized, and it is not SQL change tracking. A `COMMIT` request with a poisoned segment must not be reported as successful; the test adapter enforces production-equivalent logical validity and records the failed attempt. A recognized typed business error alone does not poison a transaction.

A simplified usage scenario demonstrates the two distinct assertion surfaces:

```java
@Test
void renamingRecordsCommitAndPendingState() {
    harness.inRollbackOperation(scope -> {
        ThingService service = scope.resolve(ThingService.class);
        Result<Thing, ThingError> result = service.rename(THING_ID, 1, "Updated");

        assertEquals(TransactionDecision.COMMIT,
            scope.transactionObservation().requestedDecision());
        assertTrue(result.isSuccess());

        Thing row = scope.inspect(db -> db.findThing(THING_ID));
        assertEquals("Updated", row.name());
        // Other scoped collaborators can be resolved and inspected here.
        return null;
    });
    // Harness rolled back the physical PostgreSQL transaction.
}
```

The example uses illustrative `Result` and inspector methods, not pre-existing project APIs. The test checks the application decision and **pending data**, never asserts that the requested commit physically occurred.

### Restrictions and failure semantics

Rollback mode intentionally models **one application transaction segment per harness execution**. A second, sequential `Transactions.execute(...)` or a nested `execute(...)` fails fast with an explicit unsupported-mode error. Test-only SQL inspection is permitted without manufacturing a second business transaction. A second independent test-harness invocation may create its own physical transaction and DI scope.

A returned `ROLLBACK` or `ABSTAIN` records the application's final intent; changes may remain visible to the test inspector until the harness's unconditional rollback. An exceptional abort is recorded and propagated, and physical rollback still occurs during teardown. A PostgreSQL error that aborts the underlying transaction may prevent subsequent SQL inspection; tests may still inspect recorded exceptions/decisions or use a real-commit scenario to verify physical effects. Rollback-only state is sticky for the simulated segment. The mode neither emulates savepoints nor purports to preserve the effects of sequential physical transactions.

All work requiring the physical test transaction executes on its owner thread. Preemptive timeouts, detached asynchronous work, listener callbacks and independent connections cannot be assumed to participate. Such behavior is rejected or excluded from this test mode; outbound effects that would escape the test transaction use controlled fakes or are disabled. An event published during the test is not evidence of durable publication or after-commit delivery. Tests also cannot rely on PostgreSQL sequences being rolled back, on common mutable seed records surviving conflicting updates, or on uncommitted changes being visible from other connections. Shared, **committed and immutable** baseline fixtures and test-unique write keys allow parallel execution where PostgreSQL locks and uniqueness constraints do not introduce unintended contention.

The test implementation is selected exclusively in test wiring and must be impossible to activate accidentally in the normal production application configuration. Tests prove that the test transaction manager, its datasource, jOOQ/JPA accessors and the harness's inspector use the same physical connection/transaction where required. Test-only inspection and outcome recording are not new production transaction APIs.

## Real-commit workflow integration tests

Workflow integration tests use the **unmodified production operation dispatcher, DI scope and `Transactions` implementation**, backed by the real Spring transaction manager and PostgreSQL. `Transactions.execute(...)` physically commits or rolls back before returning, as specified by P1.3b. Any producer publication/registry obligation is committed atomically with the originating business mutation, and after-commit consumers operate in **independent** scopes and physical transactions. A test cannot fake this boundary by replacing commit with rollback.

### Disposable fixture lifecycle

The isolation unit is a **test scenario**, with a pristine, known database state before execution and no persistent effect observable by other scenarios afterward. The preferred initial mechanism is one reusable PostgreSQL server/container with **a separate disposable database per independent fixture**. A fixture owns its datasource/configuration, migrations, deterministic committed seed data, application context and background workers from initialization through teardown. Real-commit tests may run concurrently when their database and external resources are independent.

A physical database is preferred over a separate schema because ShareWise may use several context-owned schemas, generated SQL/JPA mappings, Flyway metadata and a Modulith registry. The final granularity (per test method versus per test class) may be optimized during planning, but **class-level reuse alone is not isolation between methods**: reused fixtures require proven deterministic reset/quiescence or must group work into one scenario. Spring context caching and datasource properties must not allow independently named test databases to reuse a context pointing at another fixture. No test may depend on execution order, a prior test's changes, or manually chosen globally unique IDs as its sole isolation strategy.

The fixture starts and migrates its database **before** bootstrapping the matching Spring application context, establishes seed state, and then runs the scenario with actual commits. Teardown first prevents new scheduled/listener activity, waits for or safely stops in-flight tasks, closes the context and connections, and finally drops the database. Failed initialization and assertions still trigger cleanup; a database must never be dropped while its application context or workers are using it. Fixture provisioning failures fail or explicitly skip the affected integration gate according to CI policy; they are never silently replaced by H2.

### Messaging, concurrency and recovery

The real-commit suite verifies producer atomicity, durable publication intent, after-commit visibility, independent consumer transactions, atomic inbox or equivalent idempotency, redelivery and failure recovery against actual PostgreSQL. Assertions wait for observable effects with bounded deadlines, without arbitrary sleeps. Where useful, Spring Modulith's module-test facilities may provide bootstrapping or observation, but its `Scenario.stimulate(...)` starts a Spring transaction; tests must not place the strictly transaction-owning ShareWise executor inside that incompatible ambient transaction. Direct dispatch and independent observation are valid alternatives.

Representative scenarios include committing business state with its durable registry record, an unsuccessful producer leaving neither persisted, two concurrent consumers racing on the same event ID, a consumer committing before acknowledgment failure, and recovery by a **fresh application context** from outstanding persisted publications. External broker availability, transport acknowledgments and cross-service deployment tests remain future integration requirements when those technologies are introduced.

## Parallel execution, deterministic fixtures and CI

Parallelism is permitted only after isolation of **all mutable resources**, including PostgreSQL rows/locks/sequences, application contexts, Spring test overrides, listener executors, file paths and external mocks. Rollback operation tests may share a database only with committed immutable seed data and disjoint writes; transactional isolation alone does not make conflicting concurrent tests independent. Real-commit tests use disposable databases and independently configured contexts or another mechanism shown to provide equivalent isolation. A deliberately shared external resource may require an explicitly serialized suite, but it does not justify globally serializing all integration tests.

JUnit tags or Maven Surefire/Failsafe profiles distinguish unit, rollback-operation, real-commit-workflow and optional future deployed E2E tests; separate Maven projects are **not** required by this contract. Test-specific beans, profiles and fixture configuration cannot leak across test classes through cached Spring contexts. CI clearly reports which suites ran, which were skipped for environmental reasons, and which acceptance guarantees remain unverified. Unit tests remain entirely offline and database-independent; PostgreSQL integration tests require a provisioned compatible server/container and do not silently pass using H2.

## Acceptance criteria

Each case specifies the setup/stimulus and observable result. Where a required Spring Modulith, inbox or broker component is not yet implemented, the case becomes an acceptance dependency for its owning integration task; it must not be marked as executed until that component is present.

| ID | Setup / action | Expected evidence |
| --- | --- | --- |
| **IT-01** | Open a test-only operation scope; resolve the same operation-scoped bean through two collaborating services. | Shared identity and state in one scope; a second invocation gets a different instance. |
| **IT-02** | Invoke the harness successfully, exceptionally and with a failed destruction callback. | Real Spring scope cleanup executes once per owned resource; remaining cleanup is attempted and original failures remain diagnosable. |
| **IT-03** | Retain a scoped resource after the harness exits and invoke it; also attempt wrong-thread or nested scope use. | Expired/cross-thread resources and forbidden nesting are rejected according to the production operation-scope contract. |
| **IT-04** | In rollback mode, perform one successful write operation and inspect its observation and pending row using the same scope and transaction. | Recorded `COMMIT` and typed result match application output; pending row is visible to the inspector; no row persists after harness teardown. |
| **IT-05** | Return `ROLLBACK` or `ABSTAIN` after a write, then inspect the same test transaction. | Exact logical outcome is recorded; inspection may still see pending writes; final test rollback removes them. The test does not claim an immediate physical rollback. |
| **IT-06** | Mark the segment rollback-only, then return `COMMIT`; separately return an ordinary typed not-found error. | Poisoned commit is rejected and observed; ordinary typed error does not implicitly poison a segment. |
| **IT-07** | Throw from the application callback; separately produce a PostgreSQL error that aborts its transaction. | Exceptions and state are recorded; teardown rolls back; assertions do not require further SQL in an aborted transaction. |
| **IT-08** | Invoke a second or nested `Transactions.execute(...)` within one rollback-mode harness execution. | Fail-fast unsupported-mode error; no apparent independently committed transaction. Test inspection does not count as a second execution. |
| **IT-09** | Run compatible rollback-mode tests concurrently against committed, immutable fixtures with disjoint write keys. | No data leakage, order dependence or incorrect shared DI scope state; no reliance on rolled-back sequence values. |
| **IT-10** | Start one real-commit workflow fixture, migrate/seed it, invoke an operation and observe state from another connection. | Production commit is physically durable; teardown closes the context/workers and discards the database. |
| **IT-11** | Run two real-commit fixtures concurrently, then rerun either in a different order. | Equal pristine starting state and independent changes; each context connects to the correct disposable database. |
| **IT-12** | Within one DI scope execute sequential production segments: commit A, roll back B, then read independently. | A remains committed, B does not; no stale database facade crosses segments. |
| **IT-13** | Commit or abort a producer operation that publishes a durable Modulith event. | Atomic persistence of business state and publication obligation on commit; neither persists on rollback; after-commit delivery never substitutes for that atomicity. |
| **IT-14** | Deliver the same event concurrently and retry after a consumer failure or lost acknowledgment. | Consumer business effect and idempotency marker are atomic; replay has no duplicate business effect and failures remain recoverable. |
| **IT-15** | Shut down the application with an outstanding/failed durable publication; start a fresh context against its fixture database. | Publication is recovered and processed idempotently using real commit boundaries; teardown waits for workers to stop. |
| **IT-16** | Exercise test/production configurations and an environment missing PostgreSQL. | Mock `Transactions` and inspector cannot appear in production wiring; required integration checks fail/are explicitly unavailable instead of silently using H2 or passing. |
| **IT-17** | Run a rollback-isolated operation that emits an application event or invokes an external port. | No uncontrolled asynchronous or external effects escape the test transaction; no after-commit delivery or durable publication is claimed by the rollback test. |

The [DI architecture](../../../stack/dependency-injection.md) and [P1.3b transaction specification](../p1.3-transactions/specification.md) remain the source of truth for production scope, transaction and resource guards. P1.3c tests verify those contracts rather than defining different ones.

## Deferred decisions and implementation handoff

Implementation planning selects the exact harness API, test bean overrides, observation representation, PostgreSQL fixture provisioning and Spring-context caching strategy. These choices must satisfy the test guarantees without changing production `Transactions` signatures, `TransactionOutcome<T>` semantics, permitted sequential physical segments, or operation-scope ownership.

The rollback mode is deliberately narrower than production. It may require a **testable seam** for scope activation and a **test-only segment/inspection capability** recognized by guarded persistence access; this is acceptable as long as it preserves production fail-closed behavior and is not exposed as a business-facing transaction operation. If the initial implementation demonstrates that such integration cannot be made safe without altering production semantics, the rollback mode returns to design review rather than weakening those semantics.

No general SQL change recorder, savepoint emulator, automatic retry policy, snapshot database cloning, distributed transaction manager, embedded broker, or cross-service test orchestrator is required. A separate `plan.md` follows specification approval, with implementation increments and final reconciliation of the affected developer documentation. The task remains a proposal until the agreed acceptance evidence exists.
