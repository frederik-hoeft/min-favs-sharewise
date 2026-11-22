# P2.2a - Group creation and overview: implementation plan

**Status:** Approved plan (November 21, 2026). Phases A–D are approved (Phase D and Phase C on November 22, 2026); P2.1a has merged and the branch is rebased onto `main`; Phase E is implemented and awaits Gate E approval. It implements the approved [P2.2a specification](specification.md). **Execution gate:** each phase requires human review and approval before the next phase begins, following the [AI development workflow](../../../development/ai-development-workflow.md). A checked box means implemented, validated **and** approved.

## Baseline and prerequisites

- Work on `task/p2.2a-group-creation`, branched from `main` at `d1101fb` (P2.1.0 merged). The approved specification and this plan are committed and pushed to the task branch before Phase A starts.
- Reuse the implemented foundations: `IdentityContext` and `IdentityTestFixture`, `OperationIngressDispatcher`, `Transactions`, `JooqAccess`, `Result`, `ApiProblems`, the Integration/E2E test bases and the frontend `ApiClient`. Do not add a second dispatcher, transaction or problem-mapping mechanism.
- Follow repository rules: explicit Java local types (no `var`), Maven for the backend, npm for the frontend, and the [frontend agent notes](../../../../src/frontend/AGENTS.md) before editing Next.js code.
- **Parallel work:** before each phase, fetch and inspect `origin/task/p2.1a-auth` and `origin/main`. If P2.1a has merged, rebase onto `main` before the phase begins, so overlap (S4, S5) is resolved at a phase boundary and not inside a reviewed increment.
- **Tooling in the current dev environment:** JDK 25, Maven and Node 24/npm are available; Docker and `psql` are not, and the PostgreSQL service `postgres:5432` was not reachable at planning time. Phases B and C need PostgreSQL for `*IT`/`*E2E` evidence: either the dev-container Compose service or the GitLab CI Integration/E2E jobs. Frontend checks need `npm ci`. A missing service is reported as unavailable infrastructure, never as passing tests.

## Phase A - Module, domain values and application services

- [x] Create the `groups` module `package-info.java` with the declared dependencies from the specification. Publish `shared.infra.transactions` and `shared.infra.persistence.jooq` as the named interfaces `shared::transactions` and `shared::jooq`, using the same declarations as P2.1a so the later merge is identical on both sides (S4, GM-11).
- [x] Implement `GroupName`, `GroupDescription` and `GroupRole` with the normalization and validation rules (code points, trimming, control characters) and their unit tests (GM-09, GM-T05 domain part).
- [x] Define the application contracts: commands/queries, `GroupDetails`/`GroupSummary`, typed failures (`UNKNOWN_USER`, `GROUP_NOT_FOUND`, `ADMIN_REQUIRED`, `VERSION_CONFLICT`, validation) and the use-case-oriented ports for user registry, creation, reads and metadata replacement.
- [x] Implement caller admission as the single admission service and the four operation-scoped application services. Each performs its work in one `Transactions.execute(...)` segment and follows the check order and group mutation protocol from the specification (GM-02, GM-03, GM-05–GM-08, S2, S6).
- [x] Unit-test the services through the real operation scope with `IdentityTestFixture` and in-memory port fakes: admission outcomes, authorization order, `404` equivalence for missing and non-member groups, version handling and rollback on every expected failure. The fakes stay in test sources.
- [x] Run `./mvnw -B -ntp -Punit-tests clean verify`, including `ApplicationModuleStructureTests`.

**Gate A:** The human reviews the module declaration, the named-interface publication, domain values, contracts and service logic. The Unit suite passes. No migration, adapter, controller or frontend code exists yet, and no test fake is reachable from main sources.

## Phase B - Schema, generated types and persistence adapters

- [x] Before starting, decide the jOOQ generation path from P2.1a's state (S4). If P2.1a has merged, extend its mechanism with the `groups` schema. Otherwise, generalize the profile to several context schemas and agree the shape with the P2.1a owner before committing. Either way, exactly one generation mechanism exists.
    - The task branch generalizes the profile to `jooq-codegen` with one execution per context schema. P2.1a adds a separate `jooq-codegen-identity` profile, the only textual merge conflict with P2.1a. After the merge, move the `identity` configuration into an `identity` execution of `jooq-codegen`, remove the separate profile, regenerate both schemas without source changes, and rerun the three Maven profiles.
    - Reconciled after rebasing onto `main` at `78e1ded` (P2.1a merged). `jooq-codegen` has `identity` and `groups` executions; shared generator settings are declared once at plugin level, and the effective POM shows them merged into both executions. The generated-schema check is now the cross-context `GeneratedJooqSchemasIT`: it reads the executions from `pom.xml` and regenerates each against the freshly migrated test database, so `identity` gains the freshness check that P2.1a left as planned. Both schemas regenerate byte-identically. An injected change to a committed `identity` file fails the check for that execution. Unit (289), Integration (55) and E2E (63) profiles pass against PostgreSQL 18. The development guide and the persistence stack document describe the single profile and the check.
- [x] Guard `jooq-codegen` against unmigrated databases. Without a guard, jOOQ treats a missing input schema as empty: it deletes the committed sources of that context's package and still reports `BUILD SUCCESS`. This happened in the dev container against a fresh `sharewise` database. The profile and `GeneratedJooqSchemasIT` set `onMetadataProblem` to `FAIL`, and the development guide explains how to migrate a fresh database first. Verified: against the unmigrated `postgres` database the run fails at the `identity` execution and leaves every generated file in place; against the migrated development database both executions regenerate byte-identically.
- [x] Add `db/migration/groups/V<timestamp>__groups_core_tables.sql`: `groups.group_role`, `groups.users`, `groups.groups` (including `version`, D4) and `groups.group_members` with the specified keys, checks, unique constraint and index. Verify that the migration and application roles have the expected privileges.
- [x] Generate and commit the jOOQ types for `groups`, and add the generated-schema check (GM-12, GM-T15). After the P2.1a merge the check covers every `jooq-codegen` execution (`GeneratedJooqSchemasIT`).
- [x] Implement the jOOQ adapters: idempotent provisioning and existence check for `groups.users`; creation of group plus `admin` membership with `RETURNING`; the list and detail projections with caller role and member count in single statements, using the specified ordering; metadata replacement using the group row lock, membership re-read and versioned update.
- [x] Add a test-only Group fixture for seeding `groups.users` (through the provisioning port) and additional memberships (direct SQL, because member management is P2.2b).
- [x] Integration tests: GM-T02, GM-T06, GM-T07 (adapter level) and GM-T16. E2E tests: GM-T12 (concurrent replacements) and GM-T13 (lock ordering with a concurrent demotion).
- [x] Run the Unit, Integration and E2E Maven profiles against PostgreSQL 18.

**Gate B:** The human reviews the migration against the handoff ER and the `version` extension, the generation setup, and the SQL of each adapter. All three test profiles pass on real PostgreSQL, from the dev container or CI.

## Phase C - HTTP API

**Sequencing:** this phase starts after P2.1a has merged and the task branch is rebased onto `main`. P2.1a replaces the bearer ingress with application-composed Spring Security chains (`ApiHttpSecurityConfiguration`) and rewrites the IAM ingress tests that the test-token fixture is extracted from, so building this phase on the earlier ingress would be reworked at the merge.

- [x] Extract a reusable, explicitly imported test-token fixture into `testsupport` from the merged IAM ingress tests (`PhaseDIamIngressTests`, `FullApplicationIamIngressTests`). Switch those tests to it without changing their assertions.
    - `testsupport.identity.TestAccessTokens` signs valid, expired and wrong-profile primary tokens for a fixed trusted profile and clock; the explicitly imported `TestAccessTokenConfiguration` publishes the matching codec as `iamPrimaryJwtCredentialCodec`. Both ingress tests and `IamAuthenticationHttpTests`, which declared the same codec, use it with unchanged assertions. The [backend testing guide](../../../development/tests.md#authenticated-callers) explains when to use it instead of `IdentityTestFixture`.
- [x] Implement the `groups.api.v1` controller and request/response DTOs for the four endpoints. Dispatch through `OperationIngressDispatcher`; add a Group exception/result mapping that produces the specified status codes and `code` values on top of `ApiProblems`.
    - `GroupsController` dispatches the four services; `GroupCommands` checks the body and `expectedVersion` (required, non-negative) and maps metadata violations to `name`/`description` field errors with the codes and messages the Phase D forms display; `GroupProblems` maps the typed failures to fixed-text problems. A rejected replacement reports a missing version together with every invalid metadata field. `Location` is the relative path of the new group. The module declares `shared::di`, `shared::validation` and `shared::web` in addition to its Phase A/B dependencies.
    - **Deviation from the specification's check order (row 1):** an unauthenticated request is rejected by route admission with `401 authentication_required` (and `WWW-Authenticate: Bearer`) before any handler runs, so the operation-level `401 unauthenticated` mapping is not reached through HTTP. A supplied invalid bearer gets `401 invalid_credential` from ingress.
    - The API chain denies any mapped endpoint without an explicit admission requirement. Annotate the controller with `@ApiAccess` (authenticated IAM admission) and declare `shared::web` in the `groups` module's `allowedDependencies`. Group authorization stays in the operations.
    - Map request input and `GroupFailure.InvalidMetadata` to `shared::validation`'s `ValidationErrors` and produce the `400` response through `RequestValidationException.from(...)`, following the IAM command factories, instead of a Group-specific field-error mapping.
- [x] HTTP tests through the real bearer ingress and PostgreSQL, using `IntegrationTestContext.startServlet(...)`: GM-T01, GM-T03, GM-T04, GM-T05 (HTTP part), GM-T07 (HTTP part), GM-T08–GM-T11 and GM-T14. Include the `Location` header, byte-identical `404` bodies and the rejection of an unauthenticated request by route admission.
    - `GroupHttpE2E` (7 scenarios) covers these against the servlet application. It also checks that validation precedes caller admission, that malformed bodies, path IDs and version values get `400 malformed_request`, and that a 64-emoji name is accepted. A temporary mutation that made the `404` detail request-specific failed the byte-identity assertion. `GroupTestFixture` (formerly the membership fixture) is public and also provisions users through the registry port for the service-level and HTTP E2E tests; `GroupCommandsTests` checks the field-error contract offline.
- [x] Run the three Maven profiles.
    - Unit (294), Integration (55) and E2E (70) pass against PostgreSQL 18 in the dev container.

**Gate C:** The human reviews the endpoint contract, status-code mapping and test-token fixture. A demonstration request sequence (create, list, get, replace, stale replace) runs against a locally started backend with a test-signed token, or that limitation is recorded if no PostgreSQL is reachable. Backend acceptance tests GM-T01–GM-T16 are covered.

- **Demonstration (November 22, 2026):** the packaged backend ran locally against the dev-container database with IAM authentication enabled and a throwaway development key. A user registered through `/api/iam/v1/auth/register`, so the demonstration used a real IAM access token rather than a test-signed one. Before provisioning, `GET /api/groups/v1/groups` returned `403 group_user_unknown`; after the user ID from the token's `sub` was inserted into `groups.users` as the application role, create (`201`, `Location`, trimmed name), list, get, replace with `expectedVersion: 0` clearing the description (`200`, `version: 1`), the stale replace (`409 group_version_conflict`), an empty name (`400 validation_failed`), a missing group (`404 group_not_found`) and a request without token (`401 authentication_required`) behaved as specified.

## Phase D - Workspace UI

This phase depends only on the HTTP contract from the specification. If useful, it can be developed in parallel with Phases B and C, but it is still reviewed and approved as its own increment.

- [x] Add `core/groups/`: typed models matching the API, `GroupsApi` over an injected `ApiClient`, and the single client-selection hook (S5). If P2.1a has merged, the hook returns `useIdentity().api`; otherwise it returns the plain client.
- [x] Add the **Groups** navigation entry and the `/app/groups`, `/app/groups/new` and `/app/groups/view?id=…` pages with Mantine components and SCSS Modules, including empty, loading and error states and administrator-only editing with `409` handling.
- [x] Frontend tests GM-FT01–GM-FT04 with a mocked client, including axe checks.
- [x] Run `npm ci`, `npm run lint`, `npm run typecheck`, `npm run test:typecheck`, `npm test`, `npm run build` and `npm run test:static-export` in `src/frontend`.

**Gate D:** The human reviews the pages at narrow and wide widths in a local dev server (against mocked or real API responses) and the component code. All frontend checks pass.

## Phase E - Developer procedure, documentation and acceptance readiness

- [ ] Document the S3 developer procedure for provisioning `groups.users` with an explicit IAM user ID (Q1 default), including how to find one's own ID, in the [development guide](../../../development.md). It uses the application role and is clearly marked as a temporary stand-in for P2.2d.
    - The [development guide](../../../development.md#provision-local-group-users) explains how to read the user ID from the access token's `sub` and insert it as `sharewise_app`, plus an optional membership row for member-role tests. The decode command and both statements were run against the dev-container database (the membership insert in a rolled-back transaction).
- [ ] Reconcile the steady-state documentation listed in the specification: application architecture, persistence architecture, Phase 2 handoff (implemented Group tables and the `version` extension), frontend architecture and development guide. Update this plan's status and record the validation evidence.
    - Application architecture: status and the `groups` module layout and dependencies. Persistence architecture: the `groups` tables and the shared generation check. Phase 2 handoff: implemented Group tables, the `version` row, caller admission and the group mutation protocol. Frontend architecture and frontend development guide: the Groups routes, `core/groups/` and how pages obtain the authenticated client. The backend testing guide gained the authenticated-callers section in Phase C.
- [ ] If P2.1a has merged, switch the client-selection hook to the authenticated client (if not done in Phase D), link the sign-in-required state to `/auth/login`, and rerun the frontend checks.
    - Phase D had kept the plain client. `useGroupsApi` now returns a `GroupsApi` over `useIdentity().api` and lives in the Groups route components, because `core/` adapters must not depend on the app-level identity provider. The sign-in-required state links to `/auth/login`. The page tests render through `IdentityProvider` with a scripted transport instead of mocking the hook, and check that a signed-in tab sends its bearer.
- [ ] Run all backend and frontend checks, `git diff --check` and a relative-link check of the touched documents.
    - Backend: Unit (294), Integration (55) and E2E (70) pass against PostgreSQL 18. Frontend: `npm ci`, `lint` (three warnings that predate P2.2a, in `lib/api/client.ts` and `tests/identity/identity-runtime.test.ts`), `typecheck`, `test:typecheck`, `test` (109), `build` and `test:static-export` pass. `git diff --check` is clean, and a relative-link and anchor check of every Markdown file changed on the branch finds no broken links.

**Gate E:** Documentation matches the implemented system; the human approves the complete change for independent review.

## Review, acceptance and merge

- **Independent review:** a separate reviewing agent examines the complete merge request against the specification with the [review checklist](../../../../.agents/skills/engineering-quality/references/review-checklist.md). Accepted findings are fixed as reviewable increments, followed by documentation reconciliation where behavior changes.
- **Manual acceptance:** the human runs the specification's manual scenario once P2.1a's registration and login are available on the baseline. If P2.1a is not merged when everything else is approved, the human decides whether to wait for it or merge with backend and UI acceptance evidence only, recording browser acceptance as an open item.
- **Merge:** the human authorizes the merge. P2.2a is checked off in the [backlog](../backlog.md) as part of the accepted pre-merge update.

## Out of scope

- Member management and administrator transfer (**P2.2b**), invitations (**P2.2c**).
- Event-driven and lookup-based `groups.users` provisioning (**P2.2d/P2.4a**, **P2.2e/P2.1d**).
- Group deletion, Group events, attestations and a published Group Java API.
