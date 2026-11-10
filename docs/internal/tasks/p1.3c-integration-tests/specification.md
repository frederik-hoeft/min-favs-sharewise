# P1.3c - Integration-test execution and isolation

**Status:** Approved specification; implementation proceeds through individually reviewed phases. **Parent backlog item:** [P1.3](../backlog.md#phase-1---modular-application-and-persistence-foundation). This task depends on the [operation-scope architecture](../../../architecture/dependency-injection.md), [P1.3 production transaction specification](../p1.3-transactions/specification.md), [persistence architecture](../../../stack/persistence.md), and [messaging architecture](../../../stack/messaging.md). The [development workflow](../../../development/ai-development-workflow.md) defines the specification, planning and review gates.

## Goal and architectural boundary

Integration tests exercise real operation-scoped Spring dependency injection and, where required, the actual PostgreSQL transaction and event-publication infrastructure, while remaining deterministic, isolated and safe to execute concurrently when their fixtures are independent. Test code can resolve and inspect collaborating services **inside the same application-operation DI scope** as the subject under test. Transaction tests additionally distinguish an application's **requested disposition** from a **physically completed database commit or rollback**.

This is a test-infrastructure companion to P1.3a (operation-scoped DI) and P1.3b (production transactions), not a redesign of either. The production `OperationDispatcher`, `ServiceScopeFactory`/`ServiceScope`/`ServiceProvider` contracts, `Transactions` contract and `TransactionOutcome<T>` remain authoritative. The test harness shares the operation-scope machinery. Rollback-mode wiring substitutes one test-only, operation-scoped implementation of **both** `Transactions` and `TransactionSegmentAccess`, so application services and the unchanged jOOQ/JPA accessors observe the same logical segment. Any necessary hook for controlled scope activation or active-segment resource access is a narrow extension point of the existing infrastructure, not a second container, a new ambient transaction registry, or a different production lifecycle.

P1.2 supplies PostgreSQL 18 server provisioning (Testcontainers where a compatible local container runtime is available, or an externally provisioned GitLab CI service), cluster-wide role bootstrap, a migrator-role Flyway routine for an arbitrary database, and baseline persistence configuration. P1.3c consumes the resulting server endpoint and administrative credentials; it owns creation, preparation and disposal of per-fixture databases and calls P1.2's migration routine rather than maintaining parallel SQL or migration logic. **The GitLab integration-test job must work without Docker-in-Docker, Docker socket access or a Docker API inside the job.** P1.5 verifies P1.3b transaction interoperability against the real configuration as a separate Phase 1 gate. Messaging infrastructure is introduced by its owning feature tasks. P1.3c supplies repeatable **test execution and isolation**, not a second PostgreSQL bootstrap, finance business logic, a broker, a publication registry or an application-wide mocking framework. These dependencies may be developed and verified incrementally without claiming unimplemented messaging guarantees.

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

The harness uses the same per-context `ServiceScopeFactory` as production, activating its own independent `ServiceScope` without going through the dispatcher; test-facing bean resolution uses the scope-bound `ServiceProvider`, not a separate DI container. It establishes a scope and, where requested, the test transaction **before** resolving any operation-scoped bean that requires those capabilities. It closes/invalidates the scope and releases any test-owned resources in `finally`-equivalent cleanup paths. The test's physical rollback completes before the enclosing harness reports successful teardown. A test may retain an object reference for a negative lifetime assertion, but cannot extend its validity by retaining it.

### Inspecting pending state

A normal production persistence adapter must reject SQL outside an active transaction segment, including after the business callback returns. The test harness must not weaken that guard globally just to allow post-operation assertions. Instead, the rollback mode provides a **test-only inspection capability** using the still-active, test-owned physical transaction and its Spring-bound connection. It can query uncommitted rows after the application has returned its logical decision. The inspection capability is absent from production injection contracts and cannot silently start a new physical transaction or leak its connection. It does not activate a new application segment or make expired persistence facades usable again.

Inspection observes SQL state **already visible within the test transaction**. In particular, JPA changes may still be buffered in the persistence context when the application callback returns. The harness must not implicitly flush at logical segment completion or during inspection: a production `ROLLBACK`/`ABSTAIN` would not necessarily flush, and an implicit flush could introduce different failures. Tests that require SQL-visible JPA writes explicitly flush during the application callback or request a deliberately controlled, test-only flush where appropriate; neither approach is evidence of a physical commit.

For other operation-scoped state, tests resolve and inspect the relevant scoped bean directly. An inspection read is **not** a second `Transactions.execute(...)` invocation. If a normal application query service starts a separate transaction, invoking it after the tested operation is a workflow and is excluded from rollback mode. Ordinary adapter methods and business-oriented port interfaces remain unchanged.

## Rollback-isolated operation integration tests

The harness starts **one physical PostgreSQL transaction for the test execution**, then activates an application DI scope and resolves its dependencies using the rollback-test `Transactions` implementation. The test adapter runs a single application `execute(...)` callback against that already active physical transaction, records the returned `TransactionOutcome<T>` or thrown exception, and returns the same application-level result/exception behavior where possible. It **does not physically commit or roll back** when that callback finishes. The enclosing test harness performs the physical rollback after assertions, including on failure.

### Transaction substitution and segment lifetime

Rollback-test wiring provides a single **operation-scoped** bean implementing both `Transactions` and `TransactionSegmentAccess`. Constructor injection of either interface within an operation resolves to that same bean; neither production `SpringTransactions` nor a separate fake segment manager is used. The test configuration is deliberately isolated from production and real-commit workflow contexts. It must not replace the operation-scoped bean with a singleton test override. The existing `JooqAccess` and `JpaAccess` implementations and their public contracts remain unchanged.

The test-owned physical transaction is established before the application operation scope. For the one permitted `execute(...)` call, the test adapter activates a **logical segment**, captures an immutable segment identity and its original Spring transaction synchronization context, and allows guarded persistence access only during that callback. Its leases require the active owning operation scope, thread, logical segment identity, and current Spring transaction context; they reject access while the physical test transaction is suspended (including `REQUIRES_NEW` and `NOT_SUPPORTED`) and after logical completion. When the callback exits, all application segment leases expire even though the enclosing physical transaction remains open for inspection. The physical test transaction is never mistaken for a successfully committed application segment.

The test adapter records logical disposition and rollback-only state without invoking a physical commit or rollback from the application callback. Its lifecycle and validity checks may share a narrow internal guard with the production manager, but it does not copy the production manager's physical finalization state machine or introduce another ambient transaction registry.

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

The test implementation is selected exclusively in test wiring and must be impossible to activate accidentally in normal production or real-commit workflow configuration. Tests prove that both injected transaction interfaces resolve to the same test adapter, that the adapter, jOOQ/JPA accessors and inspector participate in the intended physical transaction, and that stale handles and suspended-transaction access remain guarded. Test-only inspection and outcome recording are not new production transaction APIs.

## Real-commit workflow integration tests

Workflow integration tests use the **unmodified production operation dispatcher, DI scope and `Transactions` implementation**, backed by the real Spring transaction manager and PostgreSQL. `Transactions.execute(...)` physically commits or rolls back before returning, as specified by P1.3b. Any producer publication/registry obligation is committed atomically with the originating business mutation, and after-commit consumers operate in **independent** scopes and physical transactions. A test cannot fake this boundary by replacing commit with rollback.

### Disposable fixture lifecycle

The isolation unit is a **test scenario**, with a pristine, known database state before execution and no persistent effect observable by other scenarios afterward. The default mechanism uses one PostgreSQL 18 server, supplied independently of the fixture code, with **a separate disposable database per independent fixture**. P1.2 provides the server, cluster-wide roles (bootstrapped once per server) and a migrator-role Flyway routine that accepts a target database; **P1.3c owns each fixture database's lifecycle**, without creating or deleting cluster-wide roles per fixture. The provisioner connects through a privileged administrative connection with `CREATEDB` and sufficient ownership/drop privileges, creates a uniquely named database, applies P1.2's migrations as the migrator role (including database-local schema, object grants and default privileges), seeds deterministic committed data and boots a matching application context under the application role. Real-commit tests may run concurrently when their databases and other mutable resources are independent.

### Server provisioning and GitLab CI

Server provisioning and test-database provisioning are separate responsibilities. P1.2 establishes a compatible PostgreSQL 18 server and exposes a connection configuration for administrative and application access. Locally, P1.2 may start that server through Testcontainers when a Docker-API-compatible runtime is available; alternatively, the same test suite may connect to a separately started PostgreSQL instance. P1.3c operates against the supplied endpoint through PostgreSQL/JDBC, without depending on a Testcontainers container handle or assuming that it owns the server process.

In the university GitLab CI environment, the runner provisions `postgres:18-trixie` through GitLab's `services:` mechanism. The integration-test job connects over TCP using the service alias, port and test-only administrative credentials. The job neither starts Testcontainers nor requires privileged mode, Docker-in-Docker or a mounted Docker socket. **The availability of the PostgreSQL service is a CI-runner prerequisite to verify**, not a permission inferred from a successful pipeline in another repository. The job waits for database readiness, runs P1.2's cluster-wide role bootstrap once per server, then runs P1.3c's database-per-fixture lifecycle. Administrative connections must be used only to create and drop fixture-owned databases, even when the CI-provided administrator has broader privileges; application connections must not inherit administrative privileges.

Both local and CI modes feed the same fixture provisioner with connection settings and use the same migrations, seed setup, isolated application contexts and cleanup. A missing server, insufficient `CREATEDB`/drop privileges, or failed bootstrap makes a required PostgreSQL integration gate fail visibly; it must never silently fall back to H2 or be reported as a passing test. Local Docker-based provisioning is a convenience, not a prerequisite for the externally provisioned mode. Neither P1.3c nor its CI job manages the server container's lifetime.

A physical database is preferred over a separate schema because ShareWise may use several context-owned schemas, generated SQL/JPA mappings, Flyway metadata and a Modulith registry. PostgreSQL roles are cluster-wide, whereas schemas, object grants and default privileges are database-local; each independently migrated fixture requires the same role/privilege behavior as a normal deployment. The final granularity (per test method versus per test class) may be optimized during planning, but **class-level reuse alone is not isolation between methods**: reused fixtures require proven deterministic reset/quiescence or must group work into one scenario. Spring context caching and datasource properties must not allow independently named test databases to reuse a context pointing at another fixture. No test may depend on execution order, a prior test's changes, or manually chosen globally unique IDs as its sole isolation strategy.

Database creation, cloning and deletion run on an administrative connection to a maintenance database, **outside any transaction block**. The fixture provisions and migrates the database **before** bootstrapping its application context, and verifies the application role can access the expected schemas without migration privileges. Teardown first prevents new scheduled/listener activity, waits for or safely stops in-flight tasks, closes the context and all fixture connections, and only then drops the database. Failed initialization and assertions still trigger cleanup; a database must never be dropped while its application context or workers are using it. Database names must be generated and owned by the fixture to avoid damaging unrelated databases. Fixture provisioning failures fail or explicitly skip the affected integration gate according to CI policy; they are never silently replaced by H2.

A **pre-migrated template database** is an optional provisioning optimization, not a requirement for correctness. If profiling justifies it, the provisioner may run P1.2's Flyway routine once for a versioned, migration-compatible template, close all template connections and clone each fixture with `CREATE DATABASE ... TEMPLATE ...`. The clone must preserve the intended database-local schemas, object privileges, default privileges and migration history, and be validated against current migrations. PostgreSQL does **not** copy database-level `GRANT` permissions or `ALTER DATABASE` configuration from the template, so the provisioner reapplies required database-level permissions/settings and verifies effective application-role access for each clone. Template databases must be immutable while fixtures are cloned and have no active sessions; migration changes invalidate the template. A clean, independently migrated fixture path remains available for correctness and migration-coverage tests. Any template strategy must preserve fixture isolation, demonstrate equivalent behavior in local and CI PostgreSQL 18 environments, and be justified by measured setup cost rather than assumed speed.

### Messaging, concurrency and recovery

The real-commit suite verifies producer atomicity, durable publication intent, after-commit visibility, independent consumer transactions, atomic inbox or equivalent idempotency, redelivery and failure recovery against actual PostgreSQL. Assertions wait for observable effects with bounded deadlines, without arbitrary sleeps. Where useful, Spring Modulith's module-test facilities may provide bootstrapping or observation, but its `Scenario.stimulate(...)` starts a Spring transaction; tests must not place the strictly transaction-owning ShareWise executor inside that incompatible ambient transaction. Direct dispatch and independent observation are valid alternatives.

Representative scenarios include committing business state with its durable registry record, an unsuccessful producer leaving neither persisted, two concurrent consumers racing on the same event ID, a consumer committing before acknowledgment failure, and recovery by a **fresh application context** from outstanding persisted publications. External broker availability, transport acknowledgments and cross-service deployment tests remain future integration requirements when those technologies are introduced.

## Parallel execution, deterministic fixtures and CI

Parallelism is permitted only after isolation of **all mutable resources**, including PostgreSQL rows/locks/sequences, application contexts, Spring test overrides, listener executors, file paths and external mocks. Rollback operation tests may share a database only with committed immutable seed data and disjoint writes; transactional isolation alone does not make conflicting concurrent tests independent. Real-commit tests use disposable databases and independently configured contexts or another mechanism shown to provide equivalent isolation. A deliberately shared external resource may require an explicitly serialized suite, but it does not justify globally serializing all integration tests.

JUnit tags or Maven Surefire/Failsafe profiles distinguish unit, rollback-operation, real-commit-workflow and optional future deployed E2E tests; separate Maven projects are **not** required by this contract. The required GitLab PostgreSQL job invokes the database-dependent suites using its provisioned service, while ordinary unit/component checks remain runnable without a server or Docker runtime. Test-specific beans, profiles and fixture configuration cannot leak across test classes through cached Spring contexts. CI clearly reports which suites ran, which were skipped for environmental reasons, and which acceptance guarantees remain unverified. Unit tests remain entirely offline and database-independent; PostgreSQL integration tests require a provisioned compatible server/container and do not silently pass using H2.

## Acceptance criteria

Each case specifies the setup/stimulus and observable result. Where a required Spring Modulith, inbox or broker component is not yet implemented, the case becomes an acceptance dependency for its owning integration task; it must not be marked as executed until that component is present.

| ID | Setup / action | Expected evidence |
| --- | --- | --- |
| **IT-01** | Open a test-only operation scope; resolve the same operation-scoped bean through two collaborating services. | Shared identity and state in one scope; a second invocation gets a different instance. |
| **IT-02** | Invoke the harness successfully, exceptionally and with a failed destruction callback. | Real Spring scope cleanup executes once per owned resource; remaining cleanup is attempted and original failures remain diagnosable. |
| **IT-03** | Attempt resolution through a retained provider after scope closure, from a wrong thread, or through a parent provider while an independent child DI scope is active; also attempt nested operation dispatch. | Invalid provider use and recursive business dispatch are rejected. Independent nested DI scopes remain permitted and isolated; generic escaped-object invocation is not a DI guarantee. |
| **IT-04** | In rollback mode, perform one successful write operation and inspect its observation and pending SQL-visible row using the same scope and transaction. | Recorded `COMMIT` and typed result match application output; flushed pending row is visible to the inspector without implicit JPA flushing; no row persists after harness teardown. |
| **IT-05** | Return `ROLLBACK` or `ABSTAIN` after a write, then inspect the same test transaction. | Exact logical outcome is recorded; inspection may still see pending writes; final test rollback removes them. The test does not claim an immediate physical rollback. |
| **IT-06** | Mark the segment rollback-only, then return `COMMIT`; separately return an ordinary typed not-found error. | Poisoned commit is rejected and observed; ordinary typed error does not implicitly poison a segment. |
| **IT-07** | Throw from the application callback; separately produce a PostgreSQL error that aborts its transaction. | Exceptions and state are recorded; teardown rolls back; assertions do not require further SQL in an aborted transaction. |
| **IT-08** | Invoke a second or nested `Transactions.execute(...)` within one rollback-mode harness execution. | Fail-fast unsupported-mode error; no apparent independently committed transaction. Test inspection does not count as a second execution. |
| **IT-09** | Run compatible rollback-mode tests concurrently against committed, immutable fixtures with disjoint write keys. | No data leakage, order dependence or incorrect shared DI scope state; no reliance on rolled-back sequence values. |
| **IT-10** | Provision one real-commit database via P1.2's migrator-role Flyway routine, seed it, invoke an operation and observe state from another connection. | Schema/object grants and application-role permissions match production; commit is physically durable; teardown closes the context/workers and discards the database. |
| **IT-11** | Run two real-commit fixtures concurrently, then rerun either in a different order. | Equal pristine starting state and independent changes; each context connects to the correct disposable database. |
| **IT-12** | Within one DI scope execute sequential production segments: commit A, roll back B, then read independently. | A remains committed, B does not; no stale database facade crosses segments. |
| **IT-13** | Commit or abort a producer operation that publishes a durable Modulith event. | Atomic persistence of business state and publication obligation on commit; neither persists on rollback; after-commit delivery never substitutes for that atomicity. |
| **IT-14** | Deliver the same event concurrently and retry after a consumer failure or lost acknowledgment. | Consumer business effect and idempotency marker are atomic; replay has no duplicate business effect and failures remain recoverable. |
| **IT-15** | Shut down the application with an outstanding/failed durable publication; start a fresh context against its fixture database. | Publication is recovered and processed idempotently using real commit boundaries; teardown waits for workers to stop. |
| **IT-16** | Exercise rollback-test, production and real-commit configurations, as well as an environment missing PostgreSQL. | Both transaction interfaces resolve to the same operation-scoped rollback-test bean only in that mode; the test adapter and inspector are absent from production and real-commit contexts; required integration checks fail/are explicitly unavailable instead of silently using H2 or passing. |
| **IT-17** | Run a rollback-isolated operation that emits an application event or invokes an external port. | No uncontrolled asynchronous or external effects escape the test transaction; no after-commit delivery or durable publication is claimed by the rollback test. |
| **IT-18** | Retain jOOQ/JPA facades and deferred queries from a completed logical segment while the harness's physical transaction remains active; attempt inspection and new application access. | Application handles reject execution after logical completion; the test-only inspector can still query pending data without resurrecting a segment. |
| **IT-19** | Suspend the physical test transaction with `REQUIRES_NEW` and `NOT_SUPPORTED` while a logical segment is active; access both persistence adapters. | Both reject acquisition/use while the owning Spring transaction is suspended, and valid access resumes only with the original transaction while the logical segment remains active. |
| **IT-20** | Provision parallel real-commit fixture databases using the shared server roles and P1.2 migrations; verify their application-role permissions and tear them down. | Database-local schemas/grants/default privileges are correct and independent, fixture contexts connect only to their own databases, and teardown leaves no fixture database behind. |
| **IT-21** | Run the required PostgreSQL integration gate in GitLab CI with `postgres:18-trixie` as a runner-provisioned service, no Docker API available to the job, and the normal application/migrator roles. | The job connects over TCP, bootstraps roles, provisions/migrates/uses/drops a fixture database, and reports real PostgreSQL test execution; missing service or privileges fails the gate without H2 fallback or silent skip. |
| **IT-22** | On a local host with a supported runtime, provision PostgreSQL via P1.2's optional Testcontainers path; alternatively use an external PostgreSQL 18 server. | The same P1.3c fixture provisioner and isolation tests work with either endpoint; external-server mode never requires a Docker API, and server provisioning does not duplicate fixture migrations or cluster role logic. |

The [DI architecture](../../../architecture/dependency-injection.md) and [P1.3b transaction specification](../p1.3-transactions/specification.md) remain the source of truth for production scope, transaction and resource guards. P1.3c tests verify those contracts rather than defining different ones.

## Deferred decisions and implementation handoff

Implementation planning selects the exact harness API, scoped test bean registration, observation representation, per-fixture database administration, PostgreSQL endpoint/configuration handoff from P1.2, GitLab service readiness and test-selection strategy, optional template-cloning benchmarks and Spring-context caching strategy. These choices must satisfy the test guarantees without changing production `Transactions` signatures, `TransactionOutcome<T>` semantics, permitted sequential physical segments, or operation-scope ownership.

The rollback mode is deliberately narrower than production. The agreed dual-interface test substitute, logical segment binding and test-only inspector are scoped to test configuration; a narrow shared guard/activation helper is acceptable if implementation needs it, but it must preserve production fail-closed behavior and remain outside business-facing contracts. If implementation demonstrates that the test mode cannot preserve these guarantees without changing production semantics, return to design review rather than weakening them.

No general SQL change recorder, savepoint emulator, automatic retry policy, mandatory pre-migrated database templates, distributed transaction manager, embedded broker, or cross-service test orchestrator is required. The companion [approved implementation plan](plan.md) defines reviewable increments and final reconciliation of developer documentation. Specification and plan approval authorize phased implementation, not automatic acceptance of any phase or the completed task.
