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
| Identity and authorization | [Preliminary security design](../../stack/security.md): Spring Security, IAM access JWT + rotating refresh cookie, issuer-qualified domain attestations | Validate transaction/revocation semantics, token profiles, shared verification infrastructure, CSRF, permissions and optional authoritative introspection |
| Transactions | Spring `TransactionTemplate` behind the documented `Transactions` port | Explicit operation-local session/outcome; database decides atomic commit, not an HTTP request scope |
| Persistence | [Preliminary design](../../stack/persistence.md): PostgreSQL + Flyway; jOOQ or Spring Data JPA/Hibernate per use-case-oriented adapter | Validate schema generation, migrations, transaction interoperability and context-owned tables; no cross-context FK/joins |
| Event delivery | [Preliminary messaging design](../../stack/messaging.md): Spring Modulith JDBC publication registry, asynchronous listeners and consumer-owned idempotency | Producer publication committed atomically; retry/recovery and retention verified; broker externalization on extraction |
| Tests | JUnit + Spring testing, database integration tests (e.g. Testcontainers) | Integration runtime needs PostgreSQL/container provisioning beyond the supplied offline cache |
| Frontend | Existing Next.js 16 App Router, React 19, TypeScript, npm; intended static export behind Nginx | Remove starter-only Next proxy routes and server-only features before static deployment; inspect installed Next docs before editing |
| Deploy/operate | Existing Dockerfiles and GitLab CI; planned Nginx TLS termination/static assets and `/api/*` routing, later Compose/CD and metrics/tracing | Prefer reproducible deployables; extraction must be justified by measurable change drivers |

Framework introductions that modify the Maven POM or npm lockfile require **refreshing and revalidating** the separately supplied offline dependency handoff. Do not substitute uncached dependencies or change versions to mask an incomplete handoff. The initial rename also changes manifest paths and the POM hash; an old verifier cannot validate the renamed tree unchanged, even if offline compilation succeeds with its dependency cache.

## Phase 0 - Domain and implementation-design decisions

**Goal:** Resolve prerequisites for a coherent first vertical slice without attempting to decide every future policy. Keep proposals explicitly marked as such until reviewed.

- [ ] **P0.1 - Package/module layout:** agree on `de.hsh.favs.sharewise` as the application root, with direct business subpackages (`identity`, `groups`, `finance`, `files`, `notifications`) and narrow published APIs. Determine package-internal visibility, optional Modulith verification, and where cross-cutting infrastructure lives.
- [ ] **P0.2 - Transport/auth design:** review the [preliminary security decision](../../stack/security.md); settle browser → Nginx → Spring routing, static-export feasibility, IAM and attestation token profiles, login/refresh/logout, cookie and CSRF rules, key management, authorization error/retry contracts and versioning.
- [ ] **P0.3 - Database contract:** review and validate the [preliminary persistence decision](../../stack/persistence.md), including schema ownership, UUID/key policy, migration/code-generation workflow, transaction interoperability, integration-test setup and database history representation.
- [ ] **P0.4 - Domain language and user journeys:** check group invitation, participation, expense splitting, settlement, closing, reopening and archiving with the domain model; prioritize unresolved business decisions only where they block the next slice (see the [open decisions](../../data-model-design.md#open-decisions)).
- [ ] **P0.5 - Delivery scope:** define the initial end-to-end demonstrator and explicit omissions. Establish test and API conventions, frontend component/styling conventions, and documentation/review gates.
- [ ] **P0.6 - Messaging contract review:** review the [preliminary messaging decision](../../stack/messaging.md) and [alternatives](../../stack/messaging-alternatives.md); settle default-inbox exceptions, per-consumer replay/retention requirements and the broker-handoff guarantees to validate at extraction. Keep implementation-specific broker and outbox selection deferred until required.

**Gate:** Approved architecture deltas and task-level requirements for Phase 1, with decisions and deferrals recorded in the appropriate authoritative documents. No new framework is added solely for scaffolding.

## Phase 1 - Modular application and persistence foundation

**Depends on:** Phase 0 decisions needed for infrastructure.

- [ ] **P1.1:** Establish the modular package skeleton and published API conventions; add executable module dependency verification where supported. Avoid placeholder interfaces or pass-through services.
- [ ] **P1.2:** Introduce PostgreSQL, migrations, per-context schema/table ownership and targeted integration fixtures; remove or isolate starter H2 dependencies where inappropriate.
- [ ] **P1.3:** Implement a technology-agnostic transaction/session boundary on Spring transactions, including explicit commit/rollback/abstain, rollback-only poisoning, exceptional abort, lifetime and concurrency rules.
- [ ] **P1.4:** Establish typed API errors, request validation, configuration, health checks, unit and integration test conventions, and the frontend API client without relying on Next.js server-side proxy routes as the long-term architecture.

**Gate:** Modularity test catches a forbidden cross-context access; real PostgreSQL integration tests prove commit, rollback, rollback-only and concurrency rules; both applications build and run with documented commands.

## Phase 2 - Identity and group membership vertical slice

**Depends on:** Phase 1 foundation and approved P0.2 security flow.

- [ ] **P2.1:** Implement IAM registration/login/profile with Spring Security authentication, password hashing and lockout; issue short-lived identity JWTs and rotating refresh cookies with transactional family reuse detection; implement CSRF, logout, password-change invalidation, and protected APIs. Test concurrent refresh, stale sessions and password-change behavior.
- [ ] **P2.2:** Group creation, membership roles, invitation/acceptance, leave and administrator transfer; enforce the final-member and final-administrator invariants atomically.
- [ ] **P2.3:** Deliver frontend authentication and group/member/invitation flows with access-token and attestation renewal, bounded `401` retry, `403` denial handling, and no unauthorized data exposure; prevent parallel refresh rotation races.
- [ ] **P2.4:** Establish versioned membership event contracts and durable Modulith publication when the first cross-context consumer is registered. Verify atomic producer commit/rollback and stable event identities; validate consumer operation transactions, idempotent recovery and registry completion against real PostgreSQL and the selected framework version.
- [ ] **P2.5:** Introduce shared JWT profile/trust verification and typed issuer-qualified claims; issue short-lived, IAM-session-bound Group Management attestations from authoritative role state. Test wrong issuer/audience/token type, cross-group privilege confusion, role downgrade, session mismatch and key rotation. Keep domain authority policy out of the security library.
- [ ] **P2.6:** Define optional versioned IAM-session and domain-permission revocation events for earlier service-local rejection. When a consumer needs this fast path, implement an idempotent cache with expiry/reconciliation, test lost, duplicated and out-of-order events plus permission restoration, and verify authorization remains correct without the cache.

**Gate:** An authenticated user can create a group, invite another user and manage membership end to end; token renewal, authority-scoped authorization, unauthorized actions, races and final-administrator/member edge cases are covered by tests.

## Phase 3 - Finance actor synchronization and accounting foundations

**Depends on:** Group contract and transaction foundation.

- [ ] **P3.1:** Define and persist FinancialGroup lifecycle/accounting currency and the minimal FinancialActor view. Group membership remains authoritative; leave and rejoin preserve the same actor identity.
- [ ] **P3.2:** Initialize the `FinancialActor` projection from existing memberships through a coordinated Flyway data migration when needed. Consume versioned membership events with atomic idempotency, out-of-order handling and reconciliation appropriate to the initial modulith; test leave/rejoin and concurrent duplicate delivery.
- [ ] **P3.3:** Implement Money, Percentage, split strategies and deterministic allocation rounding as pure, self-contained domain behavior with exhaustive invalid-input tests.
- [ ] **P3.4:** Establish the group-scoped write serialization and revision allocation primitive, including persistence-level uniqueness and concurrency tests.

**Gate:** Accounting calculations never use floating-point money; split totals conserve value; Finance initializes existing membership state and remains synchronized through replay-safe events; concurrent group-local writes cannot allocate the same logical revision.

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
- [ ] **P5.3:** Implement `OPEN → CLOSED`, `CLOSED → OPEN`, and terminal `ARCHIVED` as ordered financial operations with journal entries; enforce state-specific write permissions using verified Group Management attestations, with Finance lifecycle checks still authoritative.
- [ ] **P5.4:** Add settlement, close/reopen/archive and historical-revision frontend views.

**Gate:** A full Mallorca-style journey works end to end. Closed groups reject expense edits while allowing settlements; archived groups are read-only; any past group revision yields reproducible balances.

## Phase 6 - Files and attachments

**Depends on:** Finance and group authorization contracts; resolve attachment history policy before editing.

- [ ] **P6.1:** Implement file storage/metadata, access policies and module-owned read/write operations.
- [ ] **P6.2:** Authorize uploads/downloads using published Group/Finance capabilities, not an arbitrary file ID or cross-context table join; define retention and lifecycle behavior.
- [ ] **P6.3:** Associate attachments with expenses and implement their chosen history semantics and UI.

**Gate:** Unprivileged access is denied; file ownership and historical attachment behavior are explicit and covered by tests. Domain attestations follow the common trust model; generic file ACLs and externalized authorization adapters remain optional.

## Phase 7 - Notifications and durable integration events

**Depends on:** Identity/Group events and initial delivery semantics.

- [ ] **P7.1:** Implement notification event consumption and durable delivery/recovery policies on the existing registry, including deduplication, cleanup/retention, dead-letter or operator resolution, and replay-safe consumers. Event contracts and producer publication infrastructure are established with the first subscriber in P2.4.
- [ ] **P7.2:** Implement notification recipients, templates, channel policy, delivery attempts and auditability; choose a minimal delivery channel for the first end-to-end demonstration.
- [ ] **P7.3:** Surface notification preferences/status in the frontend where useful; ensure notification failures never invalidate committed Finance operations.

**Gate:** Duplicate and failed delivery scenarios are tested; no finance transaction is rolled back due to an asynchronous notification failure. Event-publication records and business financial history are distinct.

## Phase 8 - Delivery, deployment and operational readiness

**Depends on:** A working modulith MVP, though CI/build improvements may land earlier.

- [ ] **P8.1:** Replace the starter frontend proxy with a verified Next.js static export; deploy assets through Nginx with TLS termination and same-origin `/api/*` proxying. If introspection is deployed, deny its public route and prevent direct public backend bypass. Finish repeatable backend/frontend container builds and local Compose integration with PostgreSQL; document credentials, cookie/proxy headers and health probes.
- [ ] **P8.2:** Harden GitLab CI build/test/lint, database integration execution, image creation and automated deployment to the course target environment, with secrets kept out of the repository.
- [ ] **P8.3:** Add structured logs, actionable errors, basic metrics/traces, backup/recovery considerations and smoke tests for primary user journeys.

**Gate:** A clean deploy runs the full journey against PostgreSQL with repeatable tests, diagnosable failures and a documented deployment/rollback procedure.

## Phase 9 - Evaluate and implement selective service extraction

**Depends on:** Observable modulith behavior and a concrete change driver; not automatic because a lecture reaches the microservices topic.

- [ ] **P9.1:** Record load, ownership and operational reasons **for or against** extracting specific contexts. Identify contracts, data ownership and consistency transitions before selecting a service.
- [ ] **P9.2:** Prepare one justified bounded context for extraction: isolate its data and migrations, preserve its published API/event contracts, and replace in-process adapters with remote integration. Configure independent token validation, issuer trust and audiences. If authority introspection is justified, restrict its network exposure and authenticate/authorize calling services. Define cutover and rollback, keeping the local consumer active until the replacement delivery path is verified.
- [ ] **P9.3:** Establish broker publication/subscription through the existing Modulith integration contracts, selecting registry-backed externalization or a supported outbox integration where appropriate. Validate durable producer intent, broker confirmation, retry, ordering and duplicate-safe cutover before retiring local delivery; retain custom outbox infrastructure only as a justified alternative.
- [ ] **P9.4:** Demonstrate resilience under network faults and delayed/duplicate events; refine tracing, dashboards and deployment runbooks for the distributed system.

**Gate:** Original business invariants remain testable across service boundaries. A verified broker-backed publication and subscription path exists before the local listener is disabled; outstanding publications are drained or safely transferred, the overlapping paths are duplicate-safe, reconciliation completes and rollback is viable. Broker outages and crashes leave committed changes with recoverable publication intent. The extracted context owns its storage and can deploy independently.

## Cross-cutting review checks

For **every** implementation patch: verify the affected public contracts and exact domain invariants; include focused failure/concurrency tests, run applicable build/lint checks, update `docs/` to describe the current steady state, and record remaining environmental gaps. Keep the current approved baseline intact until review.

**Deferred, not forgotten:** currency conversion, inactive-actor eligibility, expense authorship and settlement authority, attachment history, deletion/retention, notification template/channel history, and distributed file authorization. Their authoritative tracking location is the [domain model's open decisions](../../data-model-design.md#open-decisions); resolve each before its dependent feature phase, not by assuming a database shape.
