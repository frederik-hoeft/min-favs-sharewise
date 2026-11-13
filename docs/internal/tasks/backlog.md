# ShareWise implementation backlog

**Status:** Active implementation roadmap; P1.3a operation-scoped DI and P1.3b transaction management are complete and approved. Phases are ordered by technical and functional dependencies, **not** by calendar sprints or team capacity. Each phase is a reviewable milestone that may be decomposed into smaller requirements/plan/implementation patches. Completion requires the listed acceptance gate, review, and an update to the authoritative documentation.

The existing [architecture](../../architecture.md) and [domain model](../../data-model-design.md) define the intended boundaries, domain language, transaction model, and invariants. This backlog schedules work against those contracts; it is **not** a replacement for them. The [development guide](../../development.md) describes the actual starter dependencies, which should not be mistaken for future framework commitments. The backend contains the greeting endpoint, operation-scoped DI/dispatch and Spring-backed transaction and jOOQ/JPA access infrastructure; business modules and PostgreSQL persistence are still planned. The frontend retains the greeting flow.

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

- [x] **P0.1 - Package/module layout:** agree on `de.hsh.favs.sharewise` as the application root, with direct business subpackages (`identity`, `groups`, `finance`, `files`, `notifications`) and narrow published APIs. Determine package-internal visibility, optional Modulith verification, and where cross-cutting infrastructure lives.
    - agreed as proposed: business subpackages, package-internal visibility for non-public APIs, a `shared` package for cross-cutting infra and utilities
- [x] **P0.2 - Transport/auth design:** review the [preliminary security decision](../../stack/security.md); settle browser → Nginx → Spring routing, static-export feasibility, IAM and attestation token profiles, login/refresh/logout, cookie and CSRF rules, key management, authorization error/retry contracts and versioning.
    - agreed as proposed. Versioned APIs via `/api/<domain context>/v<version>/<resource path>`
- [x] **P0.3 - Database contract:** review and validate the [preliminary persistence decision](../../stack/persistence.md), including schema ownership, UUID/key policy, migration/code-generation workflow, transaction interoperability, integration-test setup and database history representation.
- [x] **P0.4 - Domain language and user journeys:** check group invitation, participation, expense splitting, settlement, closing, reopening and archiving with the domain model; prioritize unresolved business decisions only where they block the next slice (see the [open decisions](../../data-model-design.md#open-decisions)).
- [x] **P0.5 - Delivery scope:** define the initial end-to-end demonstrator and explicit omissions. Establish test and API conventions, frontend component/styling conventions, and documentation/review gates.
    - Phases up to and including P5 are required for the MVP, later phases may extend functionality or optimize the architecture.
- [x] **P0.6 - Messaging contract review:** review the [preliminary messaging decision](../../stack/messaging.md) and [alternatives](../../stack/messaging-alternatives.md); settle default-inbox exceptions, per-consumer replay/retention requirements and the broker-handoff guarantees to validate at extraction. Keep implementation-specific broker and outbox selection deferred until required.

**Gate:** Approved architecture deltas and task-level requirements for Phase 1, with decisions and deferrals recorded in the appropriate authoritative documents. No new framework is added solely for scaffolding.

## Phase 1 - Modular application and persistence foundation

**Depends on:** Phase 0 decisions needed for infrastructure.

- [ ] **P1.1:** Establish the modular package skeleton and published API conventions; add executable module dependency verification where supported. Avoid placeholder interfaces or pass-through services.
    - packages are added with vertical slices
    - P1.1 should publish the existing `shared.infra.di` public contracts through a Spring Modulith named interface (`shared::di`); `shared.infra.di.internal` is implementation-only, and additional shared infrastructure gets separately named APIs as needed
- [ ] **P1.2:** Introduce PostgreSQL, migrations, per-context schema/table ownership and targeted integration fixtures; remove or isolate starter H2 dependencies where inappropriate.
- [ ] **P1.3 - Operation scopes and transactions:** deliver the following related foundations with independent reviewable steps:
    - [x] **P1.3a - DI scope:** implement the [reviewable DI-scope specification](p1.3a-di-scope/specification.md) using [inbound-agnostic operation dispatch and scoped Spring bean lifetimes](../../architecture/dependency-injection.md), including deterministic disposal and scope/provider misuse guards. **Implemented and accepted; the steady-state DI contract lives in `docs/architecture/dependency-injection.md`.**
    - [x] **P1.3b - Transactions:** implement the [application-owned transaction contract](p1.3-transactions/specification.md) and its [implementation plan](p1.3-transactions/plan.md) on Spring's programmatic transaction manager, with sequential physical segments, deferred outcomes, rollback-only poisoning and guarded jOOQ/JPA access. **Implemented and accepted with Spring/fake-manager and H2 evidence; the authoritative contract is in [transaction architecture](../../architecture/transactions.md). PostgreSQL acceptance remains required under P1.5.**
    - [ ] **P1.3c - Integration-test infrastructure:** refine and implement the [draft integration-test specification](p1.3c-integration-tests/specification.md), including rollback-isolated operation tests and real-commit fixtures; retain PostgreSQL transaction and concurrency evidence for the Phase 1 gate.
- [ ] **P1.4:** Establish typed API errors, request validation, configuration, health checks, unit and integration test conventions, the frontend API client and a minimal same-origin `/api/*` development route for the first authenticated browser slice. The production static-export/Nginx deployment remains in P8.1.
- [ ] **P1.5 - PostgreSQL transaction integration gate:** after P1.2 and P1.3b, verify the [P1.3b production transaction contract](p1.3-transactions/specification.md) against **P1.2's actual PostgreSQL datasource, Spring transaction manager and migrated schema**. This is a required Phase 1 acceptance dependency, not optional follow-up work or a reason to claim PostgreSQL correctness from H2 tests.
    - Prove independent-connection visibility before and after commit; mixed jOOQ/JPA/JDBC participation, explicit flush/refresh, atomic multi-adapter COMMIT/ROLLBACK/ABSTAIN, rollback-only behavior and sequential segments under PostgreSQL.
    - Exercise PostgreSQL constraint/failed-transaction behavior, real commit-time/after-commit failure consequences and stale resource guards; simulate genuinely uncertain connection outcomes with controlled fault injection where direct reproduction is unsafe, without interpreting ambiguity as rollback.
    - Use the production operation dispatcher and transaction configuration, never an enclosing Spring TestContext transaction that masks a physical commit. Reuse P1.3c real-commit fixtures where available, but P1.5 may establish a minimal isolated PostgreSQL fixture directly from P1.2 rather than depend on P1.3c's full harness.
    - Record executed evidence for TX-03 through TX-11 separately from H2/fake-manager results. Messaging publication/consumer guarantees remain with P2.4, and PostgreSQL concurrency/domain-history invariants remain with their owning persistence/feature tasks.

**Gate:** Modularity test catches forbidden cross-context access; **P1.5 passes against the real P1.2 PostgreSQL configuration**, proving transaction commit/rollback, rollback-only, mixed-adapter resource participation and the required database-specific visibility/failure behaviors; concurrency and domain invariants pass their applicable integration checks; both applications build and run with documented commands. P1.3b's H2 success alone never satisfies this gate.

## Phase 2 - Identity and group membership vertical slices

**Depends on:** The applicable Phase 1 foundations and the approved P0.2 security flow. P2.0 contract work can proceed before PostgreSQL provisioning; persistence and database-specific acceptance for the vertical slices require P1.2 and the relevant P1.3/P1.5 test infrastructure.

**Delivery model:** P2.0 establishes the smallest shared, reviewable contracts and frontend foundations needed for independent feature work. P2.1 and P2.2 are **vertical slices**: each owns its relevant application behavior, persistence, HTTP adapter, frontend journey and tests, rather than handing off a backend-only feature to a separate frontend epic. Implementations can initially use contract-conforming test doubles at neighboring module or HTTP boundaries; final acceptance integrates the real providers and verifies their database/security behavior. Task identifiers below describe the initial decomposition, not yet approved specifications or implementation plans. Detail, edge cases and subphase gates are settled through the [AI development workflow](../../development/ai-development-workflow.md).

### P2.0 - Shared contracts and frontend foundation

**Purpose:** Turn the existing [module architecture](../../architecture.md), [domain model](../../data-model-design.md), [security](../../stack/security.md), [persistence](../../stack/persistence.md) and [messaging](../../stack/messaging.md) decisions into concrete integration boundaries. Do not duplicate those decisions, create interfaces for hypothetical consumers, or implement IAM/Group business logic here. Keep context-owned DTOs and contracts independent of JPA entities, HTTP transport types and other modules' implementation packages.

- [ ] **P2.0a - Published module and identity contracts:** Define the concrete IAM/Group published Java operations, immutable inputs/results, stable opaque identity references and boundary ownership required by the planned verticals. Establish consuming-side fake adapters and compile-time/module dependency checks in coordination with **P1.1**, which owns the generic Modulith/package conventions. Include a deliberate in-process invocation/operation-scope contract; do not assume the future services already communicate over HTTP.
- [ ] **P2.0b - Authentication and authorization contracts:** Define typed verified-principal/session-binding and issuer-qualified attestation representations, trusted token profiles, validation outcomes and permission-check integration points from [the approved security design](../../stack/security.md). Provide test principals and deterministic verification fixtures so Group implementation does not depend on finished IAM. Key management, token processing and domain permission decisions remain with their eventual owners; implement the shared JOSE verification in P2.5a.
- [ ] **P2.0c - Group authority and membership event contracts:** Establish the published membership/permission projections and operations needed by Group consumers such as Finance and attestation issuance; agree on role and invitation boundary semantics where consumers require them. Define stable, versioned membership-change event identities, producer/ordering metadata and consumer-facing payloads following [the messaging architecture](../../stack/messaging.md). Provide contract fixtures without building an event registry or consumer; Group Management remains the authority for current membership and roles.
- [ ] **P2.0d - P2 HTTP contracts and integration fixtures:** Specify versioned IAM/Group routes, DTOs, error/authorization responses, CSRF/token transport semantics and representative mock HTTP responses for independent frontend/backend development. Extend **P1.4**'s common validation/error and same-origin development/API-client conventions instead of creating another API infrastructure layer. Verify schemas/fixtures and negative authorization cases at the boundary; detailed feature-specific schemas can be finalized in their owning slice specifications.
- [ ] **P2.0e - Frontend design foundation and static landing page:** Review and select the UI component strategy, design tokens, palette, typography, spacing, theme architecture, accessibility and responsive conventions for Next.js/React/Tailwind. Implement a **basic static ShareWise landing page** with generic application information as a working demonstrator of the shared app shell, navigation, representative reusable components, light/dark theming (including a working toggle) and mobile/desktop behavior. Establish proportionate frontend CI/quality checks (lint, TypeScript, build and initial component/accessibility testing), building on existing jobs. Demonstrate compatibility with the intended static-export architecture; the full Nginx/runtime migration and deployment hardening remain **P8.1/P8.2**. No functional account, group or finance UI is included in this task.

**P2.0 gate:** Shared contracts and associated fakes/fixtures are reviewed and executable; module dependency tests reject forbidden implementation coupling; the landing page provides a tangible, responsive and accessible design reference with verified frontend checks. The foundation allows IAM, Group Management and frontend work inside their respective vertical slices to start independently. No production business implementation or completed PostgreSQL integration is claimed by this gate.

### P2.1 - IAM vertical slices

- [ ] **P2.1a - Registration and initial authentication:** Implement account creation, credential hashing, authentication/lockout, initial persisted login-session issuance, short-lived IAM JWT and secure refresh cookie, CSRF-protected entry endpoints, registration/login UI and unit/HTTP/PostgreSQL tests. Use P2.0 contracts and shared token infrastructure (P2.5a). This slice makes login demonstrable without claiming complete session-renewal acceptance.
- [ ] **P2.1b - Refresh, logout and session lifecycle:** Complete transactional refresh-family rotation/reuse detection, idle/absolute expiry, revocation, cleanup, cross-instance concurrency, and logout. Add browser in-memory bearer state, bounded `401` renewal, Web Locks/in-tab single-flight for cookie mutations, ambiguous-outcome reauthentication and appropriate session/logout UX. Test lost/reordered responses, parallel tabs, CSRF bootstrap/renewal, cross-origin rejection and physical PostgreSQL races.
- [ ] **P2.1c - Profile, credentials and session invalidation:** Implement profile views/updates, password changes, account/session invalidation and corresponding authenticated UI/API/tests. Preserve the session-binding and revocation semantics in the security architecture, including multi-session and authorization failures.

### P2.2 - Group Management vertical slices

- [ ] **P2.2a - Group creation and overview:** Implement group creation, creator membership/administrator status, metadata reads/updates, context-owned persistence and the group list/create/detail UI. Exercise the published identity contract with test principals until IAM integration is available; verify authorization and PostgreSQL consistency.
- [ ] **P2.2b - Members, roles and administration:** Implement authoritative membership/role queries and mutations, leave/removal and administrator transfer, with the member/administration UI. Enforce **at least one member and one administrator** for a surviving group, including concurrent attempts to remove/leave as the final eligible member or administrator. Use current authoritative Group state for mutations rather than treating issued attestations as a substitute.
- [ ] **P2.2c - Invitations and joining:** Implement invitation issuance, visibility, acceptance and invalidation with group/user-facing UI and API, preserving the agreed invitation and organizational-membership semantics. Test invalid/replayed invitations, concurrent acceptance, expiry where specified, and group-deletion effects. Notification delivery remains a later independent feature unless explicitly required by the accepted invitation specification.

### P2.4 - Durable membership event integration

- [ ] **P2.4a - Producer publication:** When the first actual cross-context subscriber is implemented, publish the P2.0c versioned membership facts durably from Group Management using Spring Modulith's PostgreSQL-backed publication registry, atomically with the authoritative mutation. Test event identity, commit/rollback, transaction boundaries and recovery behavior.
- [ ] **P2.4b - First consumer and delivery acceptance:** Integrate the first consumer (initially expected to be **P3.2 FinancialActor synchronization**) with independently owned operation transactions, idempotency, out-of-order handling, reconciliation and registry completion. Test replay, failure and recovery on real PostgreSQL. Coordinate this task with P3.2 rather than implementing an unused mock subscriber to declare P2.4 complete.

The **event contracts** belong to P2.0c; P2.4's durable implementation is intentionally scheduled at the first real consumer and does not block the initial IAM/Group user journey in the absence of that consumer.

### P2.5 - Shared token infrastructure and group attestations

- [ ] **P2.5a - Shared JWT/JOSE trust infrastructure:** Implement the stateless issuer-/audience-/type-qualified signing and verification adapters, trust configuration and token profiles defined by P2.0b, with typed, provenance-preserving claims and rotation tests. Keep account/session persistence in IAM and role policy in Group Management. This shared enabler can be built in parallel with IAM/Group domain logic and must be ready for P2.1a's issuance/inbound verification.
- [ ] **P2.5b - Group attestation vertical:** Issue short-lived, IAM-session-bound Group Management attestations only after authoritative role/membership checks; integrate the protected group/resource operation flow and the frontend's attestation acquisition/renewal as needed. Test identity/session mismatch, wrong issuer/audience/type, cross-group privilege confusion, refusal to issue new privileges after role downgrade, bounded validity of outstanding attestations, `401` renewal versus `403` denial, and key rotation. Preserve each consuming context's own authorization/business checks; optional distributed introspection remains deferred by [the security design](../../stack/security.md).

**Numbering:** The former **P2.3 frontend-only epic is retired**. Its authentication/session UI and browser concurrency requirements are assigned to P2.1, group/member/invitation UI to P2.2, attestation UX to P2.5b, and shared frontend design/tooling to P2.0e. This avoids a cross-team frontend handoff while retaining traceability to the prior backlog.

**Phase 2 gate:** An authenticated user can register, log in, renew/logout securely, create a group, invite another user and manage organizational membership end to end through real API and frontend flows. Verify token/session/CSRF and authority-scoped access, denial and race scenarios, plus final-member/final-administrator constraints against PostgreSQL and suitable browser integration tests. Durable membership delivery is accepted with its first consumer under P2.4/P3.2, not inferred from Phase 2 UI acceptance.

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
- [ ] **P5.3:** Implement `OPEN → CLOSED`, `CLOSED → OPEN`, and terminal `ARCHIVED` as ordered financial operations with journal entries and Finance-owned lifecycle checks. During task specification, decide whether short-lived Group Management attestations provide sufficient authorization freshness for each transition or whether high-impact operations such as terminal archival require an additional synchronous authoritative Group permission check (a possible consumer of the planned introspection capability after extraction).
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
- [ ] **P9.5 (optional):** If faster best-effort revocation is needed, introduce versioned IAM-session or domain-permission change events and consumer-local revocation state. Test duplicate, missing and out-of-order messages, permission restoration, cache expiry and correctness without the cache. Remote introspection remains a separate optional choice for operations requiring fresher authoritative decisions.

**Gate:** Original business invariants remain testable across service boundaries. A verified broker-backed publication and subscription path exists before the local listener is disabled; outstanding publications are drained or safely transferred, the overlapping paths are duplicate-safe, reconciliation completes and rollback is viable. Broker outages and crashes leave committed changes with recoverable publication intent. The extracted context owns its storage and can deploy independently.

## Cross-cutting review checks

For **every** implementation patch: verify the affected public contracts and exact domain invariants; include focused failure/concurrency tests, run applicable build/lint checks, update `docs/` to describe the current steady state, and record remaining environmental gaps. Keep the current approved baseline intact until review.

**Deferred, not forgotten:** currency conversion, inactive-actor eligibility, expense authorship and settlement authority, attachment history, deletion/retention, notification template/channel history, and distributed file authorization. Their authoritative tracking location is the [domain model's open decisions](../../data-model-design.md#open-decisions); resolve each before its dependent feature phase, not by assuming a database shape.
