# P1.3c - Implementation plan: integration-test execution and isolation

**Status:** Approved implementation plan; Phases A–E accepted. Phase F documentation and final acceptance remain in review. **Specification:** [P1.3c integration-test contract](specification.md). The [development workflow](../../../development/ai-development-workflow.md) and [documentation skill](../../../../.agents/skills/documentation/SKILL.md) govern review and final reconciliation.

## Scope, baseline and dependency gates

P1.3c adds test-only infrastructure around the existing [operation-scope architecture](../../../architecture/dependency-injection.md) and [transaction architecture](../../../architecture/transactions.md). Production `ServiceScopeFactory`, `ServiceScope`, `ServiceProvider`, `OperationDispatcher`, `Transactions`, `TransactionSegmentAccess`, `JooqAccess` and `JpaAccess` remain authoritative. Tests reuse these boundaries; production application code must not acquire a second transaction API, scope implementation or dependency-injection container.

The current Java 25 / Spring Boot 4.1.2 baseline includes the accepted operation scope, Spring transaction manager and guarded jOOQ/JPA accessors, together with **P1.2's PostgreSQL foundation**. P1.2 provides the PostgreSQL 18 service and CI configuration, cluster role bootstrap, database-local grants, migrator-role Flyway routine and a disposable database helper. PostgreSQL-backed phases consume these implemented interfaces and must still pass their own PostgreSQL execution and review gates.

P1.2 owns **server provisioning and cluster-wide roles**. P1.3c consumes a PostgreSQL endpoint and administrative/migrator/application credentials, and owns **disposable fixture databases** and test-context lifetimes. Locally the endpoint may come from P1.2's Testcontainers setup or an external PostgreSQL server. In GitLab CI the runner supplies `postgres:18-trixie` through `services:`; job code uses JDBC over TCP without Docker-in-Docker, a Docker socket or a container API. A missing server or insufficient privileges fails a required PostgreSQL gate, without substituting H2. The suite's fixture provisioning is identical for both server sources.

Keep all new test harness/fixture classes and rollback transaction implementations under test sources or an explicitly test-only module. Production wiring and real-commit fixtures must never activate the rollback substitute or expose its inspector. Add test dependencies or Maven profiles only when the corresponding implementation phase needs them; refresh the separately supplied offline dependency handoff and report unavailable PostgreSQL gates independently of Unit results. The Unit suite remains runnable without Docker or PostgreSQL; database-backed tests run in the Integration or E2E suites.

Each phase is a small reviewable patch on the **approved previous phase**. Checkboxes are marked complete only after tests, review and approval. When a later phase depends on P1.2, pause that phase rather than weakening the specification or creating a second migration implementation.

## Phase A - Shared operation-scope test harness

**Focus:** An ergonomic test entry point over the existing operation scope with correct ownership and deterministic teardown. **Acceptance:** IT-01 through IT-03 (scope-facing portions) and non-database configuration isolation in IT-16.

- [x] Define a small test-facing `OperationTestHarness` / `OperationTestScope` (final names may differ) using `ServiceScopeFactory.createScope()` and `ServiceScope.run(...)`. Expose scoped resolution to tests without placing a service locator in production services; no physical transaction starts in the scope-only mode.
- [x] Preserve the scope/thread/provider lifetime rules, isolation between independent and nested scopes, and cleanup order. Resolve services only while the scope is active; ensure failures from the callback or destruction do not silently bypass remaining cleanup.
- [x] Add focused Spring tests for identity within one scope, independence across scopes, retained providers, wrong-thread access, nested scope behavior, exceptional exit and failed destruction callbacks. Exercise the real scope implementation rather than replacing it with a mock.

**Gate A:** Focused tests and offline backend `clean verify` pass. No PostgreSQL, Docker or new persistence infrastructure is needed. Review the API and cleanup semantics before Phase B.

## Phase B - Rollback-mode logical transactions and scoped wiring

**Focus:** Replace both transaction-facing interfaces in test-only wiring, share the production lease contract, and record requested decisions without claiming physical completion. **Acceptance:** framework-level IT-05, IT-06, IT-08, IT-16, IT-18 and IT-19.

- [x] Implement one **operation-scoped** test bean that supplies both `Transactions` and `TransactionSegmentAccess`. Its logical segment has a unique identity, owner-thread/scope validation, one permitted `execute(...)`, nested/sequential rejection, sticky logical rollback-only, and observation of outcome/result/exception. Both injected interfaces must resolve to the same instance; the production manager remains selected outside rollback-mode contexts.
- [x] Capture the owning Spring transaction synchronization witness and reject acquisition/execution while a surrounding physical transaction is suspended. Segment leases expire at logical callback exit, even while a test-owned physical transaction remains active. Reuse a narrow existing guard where practical; do not duplicate the production physical finalization state machine.
- [x] Define a typed immutable observation snapshot or similarly constrained test-facing view. An explicit `COMMIT` on a poisoned logical segment fails; typed business errors do not implicitly poison it; `ROLLBACK` and `ABSTAIN` retain their exact requested decisions. No callback-level physical commit, rollback, savepoint or implicit JPA flush occurs.
- [x] Test scoped registration, resolution of both interfaces, reused accessors, stale facade and deferred-query rejection, `REQUIRES_NEW`/`NOT_SUPPORTED` suspension, callback exceptions, and scope/thread escapes. Use an instrumented Spring transaction context or limited H2 tests as preliminary evidence, explicitly not PostgreSQL acceptance.

**Gate B:** Real operation-scope and accessor guards pass focused offline tests with no PostgreSQL dependency. The test adapter cannot appear in ordinary production or real-commit contexts. Review the logical/physical boundary before Phase C.

## Phase C - Disposable PostgreSQL database fixtures

**Depends on:** P1.2's PostgreSQL 18 server/bootstrap and migrator-role Flyway routine. **Focus:** Production-compatible, disposable application-context and database isolation per independent E2E scenario. **Acceptance:** fixture portions of IT-10, IT-11 and IT-20.

- [x] Introduce a server-agnostic fixture provisioner accepting explicit administrative, migrator and application connection settings. Generate strictly owned database identifiers; issue `CREATE DATABASE` outside a transaction; invoke P1.2's migrations as the migrator role, including database-local grants/default privileges; seed committed baseline data; start a fresh, database-specific Spring application context under the application role.
- [x] Make fixture ownership explicit from successful creation to final teardown, including partial initialization failure. Stop asynchronous workers, close application contexts and connections, then drop only fixture-owned databases. Verify no contamination from Spring context caching or recycled datasource configuration; preserve cleanup errors without losing the initiating failure.
- [x] Exercise independent databases in parallel, reruns in different orders, application-role restrictions, migration completeness, isolated committed state and removal after successful/exceptional tests. Do not rely on randomly chosen IDs as the only isolation mechanism.
- [x] Keep **fresh Flyway migration per fixture as the initial correctness path**. Leave pre-migrated `CREATE DATABASE ... TEMPLATE` cloning out of the required implementation; evaluate it separately only if measurements justify it, retaining a direct-migration verification path and handling database-level grants/settings explicitly.

**Gate C:** Two independent production-configured fixtures can initialize, execute real commits, run concurrently where safe and clean up fully using only the supplied PostgreSQL endpoint and P1.2's migrator routine. No Docker API is needed by the fixture provisioner.

## Phase D - PostgreSQL rollback isolation and pending-state inspection

**Depends on:** Phase C fixture provisioning and P1.2's transaction manager/datasource configuration. **Focus:** Execute the Phase B logical adapter within one harness-owned, physically active PostgreSQL transaction and expose a separate test-only inspector. **Acceptance:** IT-04 through IT-09, IT-17 through IT-19 and PostgreSQL-backed IT-16.

- [x] Start one test-owned physical transaction **before** resolving scoped application beans; execute the single logical application segment, then allow inspection against the original Spring-bound connection/context after its leases have expired. Ensure the physical transaction is rolled back during teardown on success, assertion failure and exceptional exit.
- [x] Introduce a narrow test-only inspection API that cannot start a transaction, reactivate a segment or leak connections. Pending-state reads observe flushed SQL only; the inspector does not trigger an implicit JPA flush. Verify test accessors and inspector use the same physical transaction; independent connections cannot read uncommitted writes.
- [x] Test COMMIT/ROLLBACK/ABSTAIN observations, poison/typed-error distinctions, callback exceptions, physical PostgreSQL errors that abort the transaction, inspection after logical completion, stale jOOQ/JPA handles, transaction suspension, and the single-segment restriction. Validate teardown and independent rollback-test runs, including compatible concurrent tests with disjoint data.
- [x] Exclude or fake asynchronous listeners/external effects in rollback mode, proving that no real commit or after-commit delivery is falsely asserted. Keep messaging-specific checks as acceptance dependencies on the owning feature when its infrastructure does not yet exist.

**Gate D:** PostgreSQL-backed rollback-mode tests show logical decision observation and SQL-visible pending state **without physical commit**, followed by confirmed harness rollback. H2-only preliminary tests cannot satisfy this gate.

## Phase E - Unit, Integration and E2E execution gates

Smoke / system-level E2E checks are a deferred fourth category. They are not included in the
three Phase E Maven/CI gates; their separate system-level infrastructure will be specified when
cross-context messaging, replication and deployment are implemented.

**Depends on:** Approved Phases C/D and P1.2's PostgreSQL 18 service/bootstrap.
**Focus:** Give every backend test an explicit execution category, replace H2 with real PostgreSQL,
share the migrated database only for rollback-isolated Integration tests, and report independent CI gates.

- [x] Split Surefire/Failsafe into Unit (`*Tests`/`*Test`), Integration (`*IT`) and E2E (`*E2E`) suites with explicit Maven profiles. Unit tests must be fully offline; PostgreSQL suites must fail rather than silently skip when their service is unavailable. Include a convention guard preventing regressions in selection and H2 removal.
- [x] Provide a JUnit-root-owned, once-per-suite migrated PostgreSQL database and application context for Integration. Physical transactions and operation scopes must remain test-local and rollback-only; test data and assertions may execute concurrently on independent connections. Teardown must close the context before dropping the database.
- [x] Migrate all existing H2 and production-context tests to their appropriate suites without losing contract assertions; real commit, role/schema, MVC/HTTP and cross-transaction coverage belongs in E2E with a fresh disposable database per test. Remove H2 and the globally injected test datasource properties.
- [x] Split GitLab backend packaging, Unit, Integration and E2E into independent jobs with Surefire/Failsafe JUnit reports. Both PostgreSQL jobs use `postgres:18-trixie` as runner-provisioned TCP services and P1.2 role bootstrap without DinD, sockets, or container APIs. A missing database must fail the corresponding job.
- [x] Verify both PostgreSQL suites and per-test database teardown in the GitLab runner and against a locally provisioned endpoint where available. Verify job-level separation, executed test counts, order independence and compatible concurrent rollback operations. Do not conflate offline compilation with PostgreSQL acceptance.

**Gate E (accepted):** Three independently reported CI test jobs execute and pass, with the Unit job
entirely database-free, the Integration job using one shared migrated fixture plus independent
rollback transactions, and the E2E job creating/cleaning up scenario-owned databases with real
commits. Missing PostgreSQL, failed migrations and incomplete cleanup fail the relevant gate.
P1.5 remains a separate real-PostgreSQL transaction acceptance task.

## Phase F - Documentation, acceptance and handoff

**Focus:** Reconcile user-facing test conventions and developer setup with the implemented infrastructure and record its verified guarantees.

- [x] Update developer/test guidance with the selected test entry points, modes, PostgreSQL endpoint configuration, local optional Testcontainers prerequisites, GitLab service setup, roles/privileges, deterministic fixture ownership, failure diagnostics and commands that were actually validated. Preserve `docs/architecture` as a steady-state description, updating it only for genuinely new architectural behavior.
- [x] Verify internal links, Markdown formatting and task/backlog references, and run focused tests plus the strongest available backend checks. Record actual Unit/Integration/E2E/CI execution separately, including any deferred P1.5 and messaging evidence.
- [ ] Provide a focused operational acceptance procedure (provision server, initialize two independent databases, run rollback and real-commit suites, assert cleanup) for human review. Mark phases and P1.3c complete only after their tests, review and manual acceptance; do not silently declare blocked PostgreSQL work done.

**Documentation validation evidence:** The strict-offline Java 25 / Maven 3.10 Unit gate (`-Punit-tests clean verify`) passed **106 tests** during Phase F reconciliation. The task owner reported green Unit, Integration and E2E CI and accepted Phase E after E3; PostgreSQL-backed suites were not rerun in this documentation workspace. The operational acceptance procedure is documented in [Backend testing](../../../development/tests.md#operational-acceptance) and remains subject to human review. P1.5's independent transaction acceptance and unimplemented messaging/system-level tests remain outside this gate.

**Gate F:** Implementation, approved specification, test evidence and documentation agree. The human has reviewed the complete changes and executed or explicitly dispositioned the operational acceptance procedure; merge remains separately authorized.

## Out of scope and handoff ownership

P1.2 owns PostgreSQL server bootstrap, production schema/migrations and shared role setup. P1.5 owns independent acceptance of P1.3b's real-PostgreSQL transaction contract, which may reuse these fixtures without being absorbed into P1.3c. The messaging feature tasks own durable publication, inbox, idempotency, delivery and recovery implementation and associated IT-13 through IT-15 evidence. P1.3c supplies infrastructure for those future tests, not their business or broker implementation. Smoke / system-level E2E deployment (cross-context messaging, replication and network checks), universal retry/savepoint emulation, mandatory Testcontainers in CI, template-cloning optimizations and a general-purpose mocking framework are not required.
