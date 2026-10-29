# ShareWise implementation backlog

**Status:** Initial planning proposal; no implementation phase has been approved or started. Phases are ordered by technical and functional dependencies, **not** by calendar sprints or team capacity. Each phase is a reviewable milestone that may be decomposed into smaller requirements/plan/implementation patches. Completion requires the listed acceptance gate, review, and an update to the authoritative documentation.

The existing [architecture](../../architecture.md) and [domain model](../../data-model-design.md) define the intended boundaries, domain language, transaction model, and invariants. This backlog schedules work against those contracts; it is **not** a replacement for them. The [development guide](../../development.md) describes the actual starter dependencies, which should not be mistaken for future framework commitments. The current implementation is a Spring Boot greeting endpoint, a Next.js greeting flow, and a backend context-load test.

## Working agreement

- Implement a **modular monolith first**. Preserve the five contexts and their data ownership without introducing five processes, an early message broker, or an application-wide shared domain model.
- Deliver **vertical business slices**: API, application service, domain logic, persistence adapter, authorization, relevant UI, and tests belong to the same feature milestone where feasible. Platform foundations are separated only when several slices genuinely depend on them.
- Design and review each phase before coding. Record detailed task requirements and plans under `docs/internal/tasks/<task>/` as appropriate, including decisions, invariants, failure semantics, interface contracts, tests, and reviewable subpatches.
- Prefer ports for real external or persistence boundaries, not interfaces for every Java class. Keep SQL/JPA entities private to adapters; expose immutable, operation-focused contracts.
- All financial mutations must preserve group-local ordering, atomic journal/revision updates and historical consistency. Tests against real PostgreSQL are required for database semantics.
- Only check tasks off when their acceptance evidence exists. Design questions are not implicitly answered by an implementation convenience. Scope changes update the relevant architecture/domain docs.

## Framework projection to validate in design reviews

| Responsibility | Proposed Java / web mechanism | Decision or constraint |
| --- | --- | --- |
| Application host and HTTP API | Existing Java 25, Spring Boot 4.1, Spring MVC, Jackson, Jakarta validation | Typed REST contracts; controllers are adapters, not domain services |
| Module boundaries | Spring Modulith 2.1.x and package/API verification tests | **Candidate**, not installed; evaluate published interfaces and allowed dependencies before adopting |
| Service composition | Spring constructor injection and explicit `@Bean` composition where useful | Do not expose implementation adapters as public module APIs |
| Authorization | Spring Security with carefully chosen authentication/session strategy | Decide JWT access/refresh lifecycle, browser cookie vs header/BFF boundaries, CSRF and revocation before implementation |
| Transactions | Spring `TransactionTemplate` behind the documented `Transactions` port | Explicit operation-local session/outcome; database decides atomic commit, not an HTTP request scope |
| Persistence | [Preliminary design](../../stack/persistence.md): PostgreSQL + Flyway; jOOQ or Spring Data JPA/Hibernate per use-case-oriented adapter | Validate schema generation, migrations, transaction interoperability and context-owned tables; no cross-context FK/joins |
| Event delivery | Spring application events, optionally Modulith publication registry | Durable publication on commit; ChangeRecord is **not** an outbox; broker/externalization later |
| Tests | JUnit + Spring testing, database integration tests (e.g. Testcontainers) | Integration runtime needs PostgreSQL/container provisioning beyond the supplied offline cache |
| Frontend | Existing Next.js 16 App Router, React 19, TypeScript, npm | Deliberately choose server/client components and whether Next routes act as a BFF; inspect installed Next docs before editing |
| Deploy/operate | Existing Dockerfiles and GitLab CI, later Compose/CD and metrics/tracing | Prefer reproducible deployables; extraction must be justified by measurable change drivers |

Framework introductions that modify the Maven POM or npm lockfile require **refreshing and revalidating** the separately supplied offline dependency handoff. Do not substitute uncached dependencies or change versions to mask an incomplete handoff. The initial rename also changes manifest paths and the POM hash; an old verifier cannot validate the renamed tree unchanged, even if offline compilation succeeds with its dependency cache.

## Phase 0 - Domain and implementation-design decisions

**Goal:** Resolve prerequisites for a coherent first vertical slice without attempting to decide every future policy. Keep proposals explicitly marked as such until reviewed.

- [ ] **P0.1 - Package/module layout:** agree on `de.hsh.favs.sharewise` as the application root, with direct business subpackages (`identity`, `groups`, `finance`, `files`, `notifications`) and narrow published APIs. Determine package-internal visibility, optional Modulith verification, and where cross-cutting infrastructure lives.
- [ ] **P0.2 - Transport/auth design:** define browser → Next.js → Spring request flow, login/refresh/logout, user identity propagation, role and resource authorization, CSRF handling, error contracts and versioning.
- [ ] **P0.3 - Database contract:** review and validate the [preliminary persistence decision](../../stack/persistence.md), including schema ownership, UUID/key policy, migration/code-generation workflow, transaction interoperability, integration-test setup and database history representation.
- [ ] **P0.4 - Domain language and user journeys:** check group invitation, participation, expense splitting, settlement, closing, reopening and archiving with the domain model; prioritize unresolved business decisions only where they block the next slice (see the [open decisions](../../data-model-design.md#open-decisions)).
- [ ] **P0.5 - Delivery scope:** define the initial end-to-end demonstrator and explicit omissions. Establish test and API conventions, frontend component/styling conventions, and documentation/review gates.

**Gate:** Approved architecture deltas and task-level requirements for Phase 1, with decisions and deferrals recorded in the appropriate authoritative documents. No new framework is added solely for scaffolding.

## Phase 1 - Modular application and persistence foundation

**Depends on:** Phase 0 decisions needed for infrastructure.

- [ ] **P1.1:** Establish the modular package skeleton and published API conventions; add executable module dependency verification where supported. Avoid placeholder interfaces or pass-through services.
- [ ] **P1.2:** Introduce PostgreSQL, migrations, per-context schema/table ownership and targeted integration fixtures; remove or isolate starter H2 dependencies where inappropriate.
- [ ] **P1.3:** Implement a technology-agnostic transaction/session boundary on Spring transactions, including explicit commit/rollback/abstain, rollback-only poisoning, exceptional abort, lifetime and concurrency rules.
- [ ] **P1.4:** Establish typed API errors, request validation, configuration, health checks, unit and integration test conventions, and the minimally needed Next.js API client/BFF structure.

**Gate:** Modularity test catches a forbidden cross-context access; real PostgreSQL integration tests prove commit, rollback, rollback-only and concurrency rules; both applications build and run with documented commands.

## Phase 2 - Identity and group membership vertical slice

**Depends on:** Phase 1 foundation and approved P0.2 security flow.

- [ ] **P2.1:** Identity registration/login/profile and access + refresh lifecycle; protect APIs, verify logout and revocation behavior and reject unauthenticated/unauthorized requests.
- [ ] **P2.2:** Group creation, membership roles, invitation/acceptance, leave and administrator transfer; enforce the final-member and final-administrator invariants atomically.
- [ ] **P2.3:** Deliver frontend authentication and group/member/invitation flows with actionable failures, stable IDs and no unauthorized data exposure.
- [ ] **P2.4:** Publish versioned membership facts for downstream contexts with clear ordering and replay policy; choose durable delivery infrastructure only when the first consumer needs it.

**Gate:** An authenticated user can create a group, invite another user and manage membership end to end; unauthorized actions, races and final-administrator/member edge cases are covered by tests.

## Phase 3 - Finance actor synchronization and accounting foundations

**Depends on:** Group contract and transaction foundation.

- [ ] **P3.1:** Define and persist FinancialGroup lifecycle/accounting currency and the minimal FinancialActor view. Group membership remains authoritative; leave and rejoin preserve the same actor identity.
- [ ] **P3.2:** Implement idempotent membership event consumption with duplicate, delayed and out-of-order handling. Explicitly specify what occurs before replication catches up.
- [ ] **P3.3:** Implement Money, Percentage, split strategies and deterministic allocation rounding as pure, self-contained domain behavior with exhaustive invalid-input tests.
- [ ] **P3.4:** Establish the group-scoped write serialization and revision allocation primitive, including persistence-level uniqueness and concurrency tests.

**Gate:** Accounting calculations never use floating-point money; split totals conserve value; actor replication is replay-safe; concurrent group-local writes cannot allocate the same logical revision.

## Phase 4 - Expenses, history and balances vertical slice

**Depends on:** Phase 3 finance foundations.

- [ ] **P4.1:** Create expenses with payer, participants, strategy and stored allocation shares, enforcing group/actor eligibility and authorized authorship policy.
- [ ] **P4.2:** Correct and remove expenses via immutable record revisions and tombstones, using explicit expected revisions and typed conflict results.
- [ ] **P4.3:** Append a constrained ChangeRecord in the same transaction and implement current/historical expense reads at a consistent group revision.
- [ ] **P4.4:** Implement database-side balance projections using stored allocations, including group and per-member views; add expense entry/history/balance UI.

**Gate:** Create/correct/remove are atomic with the journal; stale edits fail predictably; historical queries reconstruct each revision; allocations sum to expenses and balances sum to zero, including concurrency tests.

## Phase 5 - Settlements and financial lifecycle vertical slice

**Depends on:** Phase 4 accounting and history.

- [ ] **P5.1:** Record, correct and remove external settlement reports, with revision history and authorization distinct from payment processing; enforce valid actor pairs and positive amounts.
- [ ] **P5.2:** Include settlements in historical and current balance queries without matching them to individual expenses or forbidding overpayment.
- [ ] **P5.3:** Implement `OPEN → CLOSED`, `CLOSED → OPEN`, and terminal `ARCHIVED` as ordered financial operations with journal entries; enforce state-specific write permissions.
- [ ] **P5.4:** Add settlement, close/reopen/archive and historical-revision frontend views.

**Gate:** A full Mallorca-style journey works end to end. Closed groups reject expense edits while allowing settlements; archived groups are read-only; any past group revision yields reproducible balances.

## Phase 6 - Files and attachments

**Depends on:** Finance and group authorization contracts; resolve attachment history policy before editing.

- [ ] **P6.1:** Implement file storage/metadata, access policies and module-owned read/write operations.
- [ ] **P6.2:** Authorize uploads/downloads using published Group/Finance capabilities, not an arbitrary file ID or cross-context table join; define retention and lifecycle behavior.
- [ ] **P6.3:** Associate attachments with expenses and implement their chosen history semantics and UI.

**Gate:** Unprivileged access is denied; file ownership and historical attachment behavior are explicit and covered by tests. Distributed scoped attestations remain optional, not a default dependency.

## Phase 7 - Notifications and durable integration events

**Depends on:** Identity/Group events and initial delivery semantics.

- [ ] **P7.1:** Define versioned integration facts and a transactional publication mechanism, with dispatch retries, idempotency and replay-safe consumers.
- [ ] **P7.2:** Implement notification recipients, templates, channel policy, delivery attempts and auditability; choose a minimal delivery channel for the first end-to-end demonstration.
- [ ] **P7.3:** Surface notification preferences/status in the frontend where useful; ensure notification failures never invalidate committed Finance operations.

**Gate:** Duplicate and failed delivery scenarios are tested; no finance transaction is rolled back due to an asynchronous notification failure. Event-publication records and business financial history are distinct.

## Phase 8 - Delivery, deployment and operational readiness

**Depends on:** A working modulith MVP, though CI/build improvements may land earlier.

- [ ] **P8.1:** Finish repeatable backend/frontend container builds and local Compose integration with PostgreSQL; document configuration, credentials and health probes.
- [ ] **P8.2:** Harden GitLab CI build/test/lint, database integration execution, image creation and automated deployment to the course target environment, with secrets kept out of the repository.
- [ ] **P8.3:** Add structured logs, actionable errors, basic metrics/traces, backup/recovery considerations and smoke tests for primary user journeys.

**Gate:** A clean deploy runs the full journey against PostgreSQL with repeatable tests, diagnosable failures and a documented deployment/rollback procedure.

## Phase 9 - Evaluate and implement selective service extraction

**Depends on:** Observable modulith behavior and a concrete change driver; not automatic because a lecture reaches the microservices topic.

- [ ] **P9.1:** Record load, ownership and operational reasons **for or against** extracting specific contexts. Identify contracts, data ownership and consistency transitions before selecting a service.
- [ ] **P9.2:** Extract one justified bounded context behind its existing public API/event contracts; replace in-process adapters with remote integration, not database sharing or synchronous call chains.
- [ ] **P9.3:** Introduce broker/outbox externalization and deliberate eventual-consistency workflows as required, with retries, idempotency, ordering, reconciliation and compensating failure paths.
- [ ] **P9.4:** Demonstrate resilience under network faults and delayed/duplicate events; refine tracing, dashboards and deployment runbooks for the distributed system.

**Gate:** Original business invariants remain testable across service boundaries. Failure injection and reconciliation are demonstrated; the extracted context owns its storage and can deploy independently.

## Cross-cutting review checks

For **every** implementation patch: verify the affected public contracts and exact domain invariants; include focused failure/concurrency tests, run applicable build/lint checks, update `docs/` to describe the current steady state, and record remaining environmental gaps. Keep the current approved baseline intact until review.

**Deferred, not forgotten:** currency conversion, inactive-actor eligibility, expense authorship and settlement authority, attachment history, deletion/retention, notification template/channel history, and distributed file authorization. Their authoritative tracking location is the [domain model's open decisions](../../data-model-design.md#open-decisions); resolve each before its dependent feature phase, not by assuming a database shape.
