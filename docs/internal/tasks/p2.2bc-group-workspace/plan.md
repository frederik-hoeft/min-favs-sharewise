# P2.2b/c - Implementation plan

**Status:** Approved by the maintainer on November 25, 2026 following the [specification](specification.md) gate. The [task-local workflow pilot](workflow.md) defines self-review, patch checkpoints and mandatory human stops. All boxes are initially unchecked; completion records distinguish agent-reviewed, human-accepted and infrastructure-unverified work.

## Baseline and common gates

Source snapshot `b1ec6649a9fa9d5f2cef1ceb41deddcf24b66c57`, local snapshot commit `09b66828c3d59724c20e284535b9820e813c6c42`. Offline baseline: 394 backend Unit tests, module architecture check, 296 frontend tests, lint, application/test TypeScript checks, Next build and static export verification passed. Supplied dependency and toolchain manifests match. PostgreSQL 18 and Docker are unavailable; Integration/E2E, generated schema freshness and Compose smoke require human execution. Chromium CDP is available; actual application screenshot harness still needs implementation outside production source. Preserve original baseline and publish ordered patch checkpoints.

For backend phases run supplied Maven offline `mvn -B -ntp -o -Dmaven.repo.local="$MAVEN_REPO" -Punit-tests clean verify`; compile real database tests and identify their outstanding execution. For frontend phases run `npm run lint`, `npm run typecheck`, `npm run test:typecheck`, `npm test`, `npm run build`, `npm run build:static` and `npm run test:static-export` with supplied offline tools/cache. Run targeted checks as needed and `git diff --check` at each checkpoint. Re-read engineering-quality each phase, self-review and iterate per workflow; record actual test counts, failures, screenshot evidence and patch predecessor/result.

## Phase A - Membership operations and workspace projection

Suggested commit: `P2.2b-A: Implement membership and role management`.

- [x] Extend Group query and mutation ports/adapters using existing generated tables; coherent authorized workspace projection, scoped membership IDs, counts and stable ordering. Preserve metadata details and list endpoints.
- [x] Add operation-scoped membership policies/services and typed failures; reuse admission, lock ordering, transactions and HTTP problem infrastructure. Implement role, remove/leave and atomic transfer endpoints. Preserve metadata version semantics.
- [x] Design and implement T01–T06, including independent-connection race and rollback coverage, real SQL projection tests and HTTP denial/validation cases.
- [x] Gate: backend Unit/module checks green; database tests compiled, unavailable execution explicitly recorded; critical self-review and incremental patch.

**Checkpoint evidence:** 398 Unit/component tests passed with the supplied offline Maven, including the Modulith architecture checks; all Integration/E2E sources compiled. Added workspace/role/target authorization, final member/admin, transfer and stale-membership Unit coverage; real PostgreSQL projection, competing departure/removal/demotion, transfer rollback and HTTP cases await execution. Self-review consolidated locked caller authorization with metadata replacement, scoped every target write to the group, removed the unused role-only lookup, used one roster SQL projection, and moved request conversion into `GroupCommands`. Database correctness remains unverified until the human gate.

**Self-review record (reconstructed from Phase A checkpoint evidence):**

- P2 - Duplicate lock/admission authorization: consolidated metadata and membership mutation access in `GroupAccess`.
- P2 - Unscoped target writes: scoped target mutations by group and membership identity, with foreign/stale target regression cases.
- P2 - Projection consistency: replaced independent lookups with one authorized roster SQL projection.
- P3 - Redundant role-only lookup: removed the unused persistence capability and centralized request conversion in `GroupCommands`.

These titles summarize the recorded review corrections, not a separately conducted retrospective review.

## Phase B - Configurable workspace shell and list

Suggested commit: `P2.2b-B: Establish responsive group workspace`.

- [x] Reuse existing `ApplicationShell`; add declarative breadcrumb and optional aside configuration, shared authentication guard and group-specific nested composition without changing URLs. Check installed Next layout/route-group/static-export guides and Mantine reference; avoid effect-based slot registration.
- [x] Add typed workspace API/resource, group-scoped lifetime and group navigation; no bearer coupling. Replace list cards with searchable rows and remove visible dummy navigation; show overview placeholder and roster/metadata in shared panel with Drawer fallback.
- [x] Preserve existing editor/version handling. Evaluate breadcrumb disclosure versus explicit header control against narrow and nested navigation; retain accessible full-name behavior.
- [x] Cover T13–T14 and affected API, shell, navigation and metadata regressions. Browser screenshot gate attempted with external mocked REST; blocked by managed Chromium URL policy (see evidence).
- [x] Gate: frontend checks including static export; browser visual review outstanding at human gate; patch.

**Checkpoint evidence:** All 298 frontend tests passed, including client-side description/name search, workspace route identity race, mobile Drawer and metadata edit/reload regressions. ESLint, application/test type checks, standalone and static builds, and HTTP static-export route/asset verification passed. URLs are unchanged; demo routes remain directly navigable for existing regression coverage, but are removed from normal navigation and workspace landing. The header uses an explicit information control to avoid conflating breadcrumb navigation and truncation disclosure with the panel toggle. Browser harness successfully launched the actual Next application and Chromium CDP, but managed Chromium policy blocked localhost navigation; no screenshot evidence is claimed. Human visual review at desktop/tablet/mobile widths and both themes remains outstanding. This environmental limitation does not prevent component/contract implementation; preserve the gate for human acceptance.

**Self-review record:**

- P2 - Server Component function serialization: replaced the Mantine polymorphic Link prop on the workspace landing with a native Next Link after prerender failed; production/static builds now pass.
- P2 - Hidden measurement overflow: clipped the breadcrumb measurement wrapper without altering its intrinsic child measurement, avoiding potential document overflow with unbroken names.
- P3 - Inaccessible row count: exposed the count as an accessible description of the group link and added a regression assertion.
- P3 - Stale test composition: exercised the authenticated boundary plus the standard shell rather than expecting the guard itself to own geometry; tested one workspace fetch and Drawer dismissal.
- P3 - Ambiguous truncated-breadcrumb interaction: retained the separate labeled information button and its control/expanded relationship, preserving full-location disclosure and ancestor links.

## Phase C - Member administration UI

Suggested commit: `P2.2b-C: Integrate member administration`.

- [x] Integrate role change, transfer, remove and leave controls with clear confirmation, self marker, pending guards, stale target/permission and uncertain outcome reconciliation. Keep current group projection and breadcrumb coherent; preserve metadata draft behavior and session transparency.
- [x] Cover T15, permission failures, simultaneous UI events, delayed results on route/session changes and keyboard/accessibility. Browser screenshot review remains blocked by managed Chromium policy; human visual review outstanding.
- [x] Gate: frontend checks, focused review against GM/GW requirements, patch.

**Checkpoint evidence:** 318 frontend tests passed; ESLint, application/test TypeScript checks, standalone/static builds and HTTP static-export verification passed. Group-scoped confirmation and exclusive mutation ownership survive Drawer closure; authoritative reconciliation, stale/uncertain outcomes, final-admin/member controls, leave navigation and route/session disposal are covered. Browser visual evidence remains unavailable as in Phase B.

**Self-review record:**

- P2 - Superseded reload result: resource reload now returns null for disposed or superseded requests; regression coverage checks both rendering and returned results.
- P2 - Ambiguous metadata save outcome: explicitly report uncertain writes and preserve safe optimistic-concurrency retry behavior.
- P2 - Missing center-view snapshot contract: added a narrow read-only workspace context and one-fetch regression test.
- P3 - Focus lost on confirmation removal: shell-owned focus restoration returns to the original desktop trigger or mobile information control, with tests; keep the aside declarative through a narrow shell context rather than callback content.
- P3 - Invalid ordinary-member test fixture: assigned another member the administrator role to exercise the intended valid group state.

## Phase D - Shared opaque application token primitives

Suggested commit: `P2.2c-D: Extract reusable opaque application token primitives`.

- [x] Extract technical generation, canonical parsing and verifier comparison from IAM into `shared::security`; use `ApplicationToken`, `ApplicationTokenGenerator` and DI-managed `ApplicationTokenParser`, redaction and defensive copies. Keep IAM session/cookie/storage policies and behavior intact. Register DI capabilities and declare Group module dependency when used; no cross-module internal imports.
- [x] Cover T07 and run full backend Unit/module checks including IAM; compile affected IAM Integration/E2E and review actual call paths for format, replay and logging regressions.
- [x] Gate: self-review security-sensitive compatibility; patch. No invitation schema dependency yet.

**Checkpoint evidence:** 400 backend Unit/component tests passed, including Modulith module structure/verification and IAM regression suites; Integration/E2E sources compiled. Shared token parser and generator are DI-managed singleton capabilities; parsed `ApplicationToken` retains only selector and verifier, and `GeneratedToken` holds the one-time bearer. IAM storage, cookie format and lifecycle remain unchanged. Group dependency declaration awaits Phase F, the first Group consumer. Real PostgreSQL IAM tests remain outstanding.

**Self-review record:**

- P2 - Duplicate generator beans in full-application E2E fixtures: removed the fixture bean and direct internal configuration import so the real shared configuration is discovered through normal application scanning.
- P2 - Insufficient independent wire compatibility coverage: added a fixed canonical bearer and SHA-256 vector independent of generation.
- P2 - Conflated wrong-selector and wrong-secret tests: independently vary the selector with a matching verifier, and reject null/incorrect-length stored verifiers.
- P2 - Missing noncanonical Base64URL alias coverage: verify rejection of nonzero trailing unused bits and null input, retaining the original bounded canonical parser behavior.
- P3 - Missing generated verifier copy assertion: added a regression test for modification of the returned byte array and redaction.

## Phase E - Invitation migration and jOOQ handoff

Suggested commit: `P2.2c-E: Define invitation persistence schema`.

- [x] Create additive Group Flyway migration with ER-defined columns/domains/checks/index and least-privilege grants consistent with repository conventions. Add schema contract tests not requiring the as-yet-ungenerated mappings. No runtime invitation adapter depending on missing types.
- [x] Run offline Unit/module gate; review migration/DDL and privileges; publish checkpoint with exact migration path, cumulative phase status and generation instructions. Explicitly record temporary schema freshness failure expected until generation.
- [x] **STOP 1 (human):** Apply ordered patches, migrate the development database, run Group-only jOOQ code generation as documented in [development setup](../../../development.md), inspect generated diff, check CI and return the generated patch and feedback. Expected generator command from backend root in dev container: `./mvnw -Pjooq-codegen -Djooq.codegen.jdbc.url=jdbc:postgresql://postgres:5432/sharewise -Djooq.codegen.jdbc.user=sharewise_migrator org.jooq:jooq-codegen-maven:generate@groups` (with configured `JOOQ_CODEGEN_PASSWORD`). Migration must have run first; no source edits to bypass generation. Human may commit migration and generated sources together upstream if appropriate while retaining the ordered local checkpoint history.

**Checkpoint evidence:** Additive migration `src/backend/sharewise/src/main/resources/db/migration/groups/V202611251310__groups_invitations.sql`; Group-owned integer domains, UUIDv7 default, local NO ACTION FK and index, verifier/expiration/usage constraints. Default SELECT/INSERT/DELETE retained, table-level UPDATE revoked and column-level UPDATE granted only for `use_count`. Four real PostgreSQL schema E2E cases cover metadata/ownership/index/privileges, defaults/FK/duplicate identity and revocation, invalid values and overflow, and upgrade preservation. These tests and affected Integration tests compiled; 400 backend Unit/component tests and Modulith verification passed in the offline `clean verify` gate. The first invocation was interrupted after tests during packaging; the complete repeat succeeded. Database migration execution, physical constraints and jOOQ generation remain unverified locally; schema freshness is expected to fail until generated sources accompany the migration. No invitation runtime code or hand-written generated mappings added.

**Self-review record:**

- P2 - Existing Group schema test rejected additive tables: adjusted the ownership contract to require the core and invitation tables and migrator ownership of all Group tables, without forbidding future additive tables.
- P2 - Runtime privilege test accepted any one CRUD grant: verify each required grant separately for the existing core tables; test invitation-specific update restrictions separately.
- P3 - Missing explicit null tests for required schema fields: added rejection cases for null selector and creation timestamp alongside the other constraints.

**Human handoff:** Phases A–E are locally self-reviewed and published, not accepted. Apply the ordered patch sequence, migrate PostgreSQL, regenerate only Group schema sources and return the generated diff; keep freshness enabled. Real database and browser acceptance remain outstanding. No merge into baseline or changes to authoritative steady-state documentation yet.

**Human gate 1 accepted:** Maintainer reported green CI and local smoke, supplied desktop/mobile screenshots and the generated schema patch (upstream `daa4273`, SHA-256 `b027893fc3d077bf4348412fd7aa1685a5e528fbe564f8ceb3e249c289c5d69d`). Restored all seven checkpoints from the archive and verified their tree IDs; applied generated patch unchanged, resulting tree `7b56979d36fa17f0ebbef4e2262c4fcb4e80c9b6`. Offline clean verify passed (400 tests); npm restore passed. No generated-schema integration review findings.

**Maintainer review follow-up (A/B):** Split membership HTTP and persistence responsibilities (`GroupMembershipController`, `GroupMembershipPort`, `JooqGroupMemberships`) while retaining a single root-lock protocol and coherent workspace read projection. Java cannot import both identically named domain/generated `GroupRole` types; use static imports for enum constants and retain qualification only at the conversion signatures. Expanded compressed SCSS declarations in both group stylesheets. No public endpoint or behavior changes. Offline backend gate (400 tests, module verification and DB test compilation), frontend lint and 318 tests passed. Self-review found no additional issues in this focused refactoring.

## Phase F - Generated schema integration and invitation backend

Suggested commits: `P2.2c-F0: Integrate generated Group schema` (human-supplied diff checkpoint), `P2.2c-F: Implement secure shareable invitations`.

- [x] Verify and apply maintainer-supplied generated patch against exact migration state, record hash/tree, run offline compile/Unit; publish integration checkpoint before dependent changes. Do not modify generated code manually.
- [x] Implement invitation ports, adapters and operation-scoped services using Group root lock and shared opaque primitives; create/list/revoke/redeem HTTP contracts with uniform unavailable failures, bounded inputs, current expiry and atomic membership/count updates. Ensure no secret in diagnostics or noncreation projections.
- [x] Cover T08–T12 including real commits, races and rollback. Audit all paths against current Group role/admission and existing IAM behavior. No new profile lookup, events or deletion path.
- [x] Gate: backend Unit/module checks and compiled DB tests; self-review and patch.

**Checkpoint evidence:** Offline clean verify passed with 404 Unit/component tests and module verification; all Integration/E2E sources compiled. Dedicated invitation controller, application service and persistence port/adapter; root-lock re-read, database wall-clock expiry, uniform unavailable responses, one-time bearer disclosure and atomic member/count mutations. Added real PostgreSQL competing redemption, revocation and expiry during lock wait, rollback, rejoin and HTTP lifecycle cases; execution awaits maintainer CI. The shared application-token mechanism is reused unchanged. HTTP expiry is an ISO instant bounded to year 9999, persisted at microsecond precision; maximum uses is an integer, and Jackson rejects fractional integer coercion globally.

**Self-review record:**

- P2 - Application summary leaked into HTTP serialization contract: introduced dedicated nonsecret HTTP response projection.
- P3 - Invitation revocation reused membership outcome type: introduced invitation-specific status.
- P2 - Ambiguous timestamp and fractional usage inputs: parse an ISO instant explicitly, reject float-to-integer coercion and add HTTP rejection cases.
- P2 - Expiry race coverage: exercise database wall-clock expiry after waiting on the aggregate lock; database execution outstanding.

## Phase G - Invitation frontend and continuation

Suggested commit: `P2.2c-G: Integrate invitation creation and acceptance workflows`.

- [x] Admin list/create/revoke UI and copy-only-on-creation behavior. Typed GroupsApi additions, error and uncertain mutation reconciliation.
- [x] Static `/invite` route and fragment capture; explicit user confirmation with authenticated transport. Add narrow safe navigation/auth continuation preserving login/register switch and no secret in return URLs or persistent storage. Preserve existing auth flows and session lifetime semantics.
- [x] Cover T16–T18 and relevant auth regression cases. Render actual application with mocked REST for invitation and group flows at representative widths; inspect screenshot and accessibility behavior (structural accessibility passed; browser environment blocks navigation).
- [x] Gate: frontend checks including static export, review security and state lifetimes, patch.

**Checkpoint evidence:** Added admin invitation management in the existing workspace central view, one-time copyable link, usage/expiry presets and custom limits, revoke confirmation, uncertain-outcome reload and authority-loss reconciliation. Static `/invite` uses fragment capture, explicit authenticated POST, root-owned ephemeral continuation through login/register, and safe exact return path; no bearer in query, persistent storage or history state. Session-lifetime invalidation and abandoned operation handling preserve IAM semantics. Shared route and asynchronous operation helpers extracted rather than duplicated. All 335 frontend tests passed, including 17 new cases; after final native-history/identifier cleanup, 27 targeted tests, lint, test typecheck and static build passed. Application typecheck, normal build and static HTTP verification passed in the preceding cycle. Structural axe coverage passed for the new admin UI. Browser inspection remains blocked by managed Chromium URL policy; maintainer must perform the new invitation flow's responsive/theme acceptance. No dependency changes.

**Self-review record:**

- P2 - Continuation bearer was a TypeScript-private enumerable property: use ECMAScript private state, publish only revision, and verify serialization cannot disclose it.
- P2 - Late authentication response could navigate an abandoned form: reuse the lifetime-scoped exclusive-operation hook and preserve the captured return destination only for the initiating page.
- P2 - Pending continuation could survive a same-path query change or a cross-tab context change: observe the exact detour through a Suspense-isolated router observer and synchronously invalidate on session changes.
- P2 - Malformed success DTOs could display unusable links or navigate incorrectly: require token shape and valid redemption outcome/ID before acknowledging; reconcile uncertain results.
- P2 - Next internal history state bypassed native history synchronization: use the documented external-history API after router effect initialization.
- P3 - Revoke confirmation focus could be restored repeatedly: consume the focus-restoration flag once.
- P3 - Provider assumed a Next routing context even in standalone rendering: tolerate missing search params, retaining fail-closed navigation cleanup.

**Implementation decision:** Use native Mantine selects for short invitation preset lists; avoid a custom dropdown dependency on browser geometry and use platform pickers on mobile. Existing group IDs remain URL query parameters; invitations are managed in the replaceable center view rather than a nested mobile modal.

## Phase H - Aggregate review and acceptance readiness

Suggested commit: `P2.2bc-H: Harden and validate group workflows`.

- [x] Review complete feature branch against specification, shared code reuse, transaction/authorization paths, concurrency and data ownership, async resource/route lifetimes, secrets, static deployment and test quality. Iterate fixes with tests and run available checks. Browser inspection of the invitation UI remains a human acceptance item (managed Chromium navigation policy).
- [x] Prepare final implementation checkpoint, aggregate summary, ordered replay manifest and manual acceptance scenarios. Earlier group screenshots were accepted at gate 1; no new invitation screenshots are claimed.
- [x] **STOP 2 (human):** Maintainer applies remaining patches, runs all real PostgreSQL suites/schema freshness and CI, production Compose smoke plus representative workflows, manual acceptance and aggregate review. Resolve findings with incremental follow-ups before documentation. Record tested revision and feedback.

**Self-review record:**

- P2 - Custom invitation usage input silently clamped invalid values on blur: disabled clamping and verified that zero produces no POST.
- P2 - Missing delayed issuance/session-replacement regression: verified that the abandoned administrator response cannot disclose its bearer or reload invitations under the replacement session.
- P2 - Missing malformed redemption HTTP boundary coverage: added non-string and unknown-field inputs, verified the intended uniform malformed-request code, non-disclosure and unchanged usage; execution awaits PostgreSQL CI.
- P3 - Revoke confirmation lacked target identification: show the selected nonsecret invitation ID and associate row actions with that ID for assistive technology.
- P3 - Membership controller depended on another controller's route constant: extracted package-local HTTP path vocabulary without changing endpoint paths.

**Checkpoint evidence:** Offline backend clean verify (404 Unit/component tests and module verification), database test compilation, frontend 337 tests, lint, both TypeScript checks, standalone and static builds passed. Full feature-branch diff reviewed, including group-root lock ordering, authorization, membership invariants, shared token compatibility, secret disposal, uncertain writes, and static navigation. No manual modifications to generated schema, no dependency changes, no authoritative documentation changes. Full database-backed tests and real Compose smoke remain unexecuted locally. Static HTTP route/asset checks (including `/invite`) and all 59 source-level deployment-contract tests passed; these do not substitute for real Compose acceptance.

**Human gate 2 acceptance checklist:**

1. Apply the post-codegen patches in order; verify CI on the resulting revision. Run the Integration and E2E profiles against PostgreSQL 18, including schema freshness and the new concurrency/rollback cases. Run the production Compose acceptance stack.
2. Provision at least three test accounts through the existing Group admission procedure (P2.2d/e remain deferred). Confirm old IAM login/refresh/password-change behavior, including concurrent renewal.
3. As administrator, create single-use, finite multi-use and unlimited invitations with and without expiry. Confirm list metadata contains no token or verifier; copy, close and confirm the secret cannot be retrieved. Try invalid usage limits and revoke the intended link, including expired and exhausted links.
4. Open a fragment link in another tab/browser, both already signed in and signed out. Exercise login and registration detours, explicit confirmation, cancellation, reload recovery, and account replacement. Verify that the token does not enter the return query, storage, HTTP request URL or ordinary logs. No join occurs until explicit POST.
5. Verify single-use exhaustion, valid already-member redemption without count/role changes, forged/expired/revoked rejection, rejoin, and role protection following administrator removal/demotion. Confirm metadata version remains independent of membership and invitations.
6. Inspect invitation creation, copy/revoke and acceptance on desktop, tablet and mobile in both themes; check long identifiers, overflow, keyboard focus, Drawer navigation and the existing workspace. Return screenshots or findings and record revision/CI/smoke evidence before Phase I.

## Phase H.1 - Group persistence projection consolidation and CI regression correction

Suggested commit: `P2.2bc-H.1: Consolidate Group persistence projections and caller admission`.

- [x] Consolidate locally established relational facts and retain typed failure semantics, existing schema/API, and the separate post-lock snapshot. Use caller admission on creation or ambiguous projection misses; leave IAM integration to P2.2d/e.
- [x] Diagnose and correct parallel component-test failures without weakening the closed-world user policy. Add application-path and actual SQL statement-count regression coverage (T19).
- [x] Update the engineering-quality skill and review checklist with operation-oriented database projections and fixture-lifetime guidance. Maintain authoritative documentation gate.
- [x] Review, compile, validate and publish an incremental patch against the Phase H tree.

**Review record:**

- P2 - Redundant admission and table-oriented query orchestration: use local-existence-aware root selection, member-scoped read projections, joined membership context with `EXISTS` predicates, and cohesive invitation issuance/redemption projections. Listing invitations no longer acquires a mutation lock; caller role and visible metadata share a read snapshot. Policy and secret verification remain in the application.
- P1 - Parallel component suites reset shared Spring singleton fixtures: reproduced six failures on the unmodified Phase H tree at four workers. Both suites now declare the same JUnit resource lock covering their fixture lifecycle, including the transaction log. The rest of the suite retains parallel execution; seeded memberships require known users.
- P2 - Consolidation risked stale authorization during root-lock waits: retain the separate fresh post-lock statement for membership changes and redemption; scope the redemption root lock to the group table. Existing real-transaction contention cases remain in the gate.
- P2 - Ordinary-member mutation projection could materialize other target facts before policy denial: restrict the target join to administrator or self and test the absent target and false invariant flags.
- P3 - A revised list projection could expose mutable list results: preserve the service's immutable list contract.
- P3 - Projection-count regression risk: assert the application port sequence and add a PostgreSQL Integration test counting actual jOOQ executions through a locally derived, segment-guarded configuration.

**Checkpoint evidence:** Original Phase H tree `e24b466987a3147c9aa27e70a4070efc600c9286` reproduced the reported class of parallel failures (19 tests, six failures). Corrected targeted component suites passed with four workers before new tests were added. Full offline `mvn -B -ntp -o -Dmaven.repo.local=<isolated-cache> -Punit-tests -DtestParallelism=4 clean verify` passed 406 tests; final `-DtestParallelism=16 clean verify` (matching GitLab CI) passed 406 tests, zero failures/errors/skips, including Modulith verification and compilation of Integration/E2E tests. The new SQL-count Integration test and existing real PostgreSQL concurrency/rollback tests are not executed in the offline environment. No frontend, dependency, migration or generated-schema change. Maintainer subsequently accepted the implementation from an acceptance-test and code standpoint, subject to the H.2 requests. Exact H.1 CI and Compose results were not supplied.

## Phase H.2 - Invitation preview and membership simplification

Suggested commit: `P2.2bc-H.2: Preview invited group and simplify administration`.

- [x] Apply the maintainer's accepted scope revision: display the invited group name after authenticated preview; remove transfer endpoint and UI; prohibit actual self-role changes while retaining promotion/demotion of other members and leave.
- [x] Reuse shared token validation and the Group resource hook, verify one-statement preview projection without lock/writes, test invalid links, stale continuation, cross-session results and retained membership invariants.
- [x] Review the implementation and publish the incremental checkpoint before reconciling documentation.

**Self-review record:**

- P2 - A replacement invitation could be cleared by an earlier preview rejection: gate preview and redemption continuations on both session lifetime and capability revision; test replacement and account-switch races.
- P3 - Existing invitation status test reused an ID for two rows: give the expired fixture its own identifier to exercise distinct list entries.

**Checkpoint evidence:** Offline backend clean verify passed 407 tests at 16 JUnit workers, including Modulith verification and Integration/E2E compilation. Frontend 340 tests, ESLint, both TypeScript checks, standalone and static builds, and static HTTP route/asset verification passed. No database or dependency changes. Browser screenshot inspection of the revised acceptance view remains a human check.

**Maintainer evidence:** The maintainer reported acceptance tests and code LGTM and provided an invitation acceptance screenshot. The two requested behavior changes are covered by H.2 and require a targeted human recheck. No explicit final CI or Compose result for H.1/H.2 was supplied. Database-backed tests compile locally but cannot be executed in this environment.

## Phase I - Documentation reconciliation

Suggested commit: `P2.2bc-I: Reconcile group management documentation`.

- [x] After human acceptance of implementation behavior, update authoritative architecture, persistence, Phase 2 handoff, security, frontend and development/testing guides as appropriate; preserve Phase 3 membership revision semantics. Record human validation results, limitations and any approved deviations in task artifacts.
- [x] Check links/anchors, consistency and diff, rerun relevant checks and publish final docs patch.
- [ ] Independent reviewers review integrated revision; fix any accepted findings and reconcile docs again if behavior changes.
- [ ] Final maintainer merge authorization and backlog completion after all required reviews, acceptance and smoke evidence (or explicit documented exceptions). Keep `main` unchanged until approval.

**Documentation review record:**

- P2 - Phase 2 handoff still described invitations and membership changes as future work, and security status described live IAM eligibility as proposed: reconcile current contracts, keeping event delivery, local-miss provisioning and attestations deferred.
- P2 - Stale transfer and metadata-version decisions appeared in the domain model, backlog and predecessor task documents: align the current model and point predecessor artifacts to the follow-up specification. Preserve the separate future membership replication revision.
- P3 - Frontend route tree and provisioning guide no longer matched the workspace or invitation flow: document declarative shell composition, authenticated name preview, in-memory continuation and a real two-account invitation procedure.

**Checkpoint evidence:** Documentation skill and engineering-quality skill explicitly reread, including documentation writing examples. Reconciled authoritative docs with the actual H.2 application contracts and retained the previous review and test history. All relative Markdown links and anchors in changed documents resolved; tracked-source encoding check, its eight regression tests and Git whitespace check passed. No production or test code changes in this phase; the H.2 build/test evidence remains applicable. Maintainer authorized documentation after acceptance feedback. Exact post-H.2 PostgreSQL CI, Compose and browser recheck evidence and independent PR review remain outstanding. P2.2a/b/c backlog completion and baseline merge require final maintainer approval.


## Phase I.1 - Production Group smoke coverage

Suggested commit: `P2.2bc-I.1: Exercise Group Management in production Compose smoke`.

- [x] Record the maintainer's passing production Compose smoke on the pre-I.1 implementation.
- [x] Extend the disposable HTTPS smoke with two real IAM accounts, temporary local Group user provisioning, group creation/listing, anonymous and outsider denial, invitation preview/redemption/consumption, live role promotion, revocation and departure. Check static group and invitation routes in both production smoke scripts.
- [x] Retain the existing IAM and stack lifecycle checks; keep new credentials out of process arguments and diagnostics. Verify the smoke orchestration and failure/cleanup paths without Docker.
- [ ] Maintainer reruns the extended smoke against the actual disposable production stack.

**Self-review record:**

- P2 - Direct fixture provisioning requires explicit deployment scope: refuse API smoke invocation without the reserved disposable project and base Compose file; test that the guard prevents container commands.
- P2 - Response parsing could expose unexpected status text in an exception: validate the status framing and report a fixed diagnostic; verify bearer and invitation data travel via stdin and malformed responses do not disclose secrets.

**Checkpoint evidence:** The existing production Compose smoke passed according to the maintainer. The extended smoke is a deployment-only change and does not modify product code or schemas. All 70 deployment tests passed (`python3 -m unittest discover -s deploy/tests -p 'test_*.py' -v`), including 14 HTTP orchestration tests; Bash syntax, source encoding and Git whitespace checks passed. A real local curl request verified the stdin configuration and JSON encoding without a Compose stack. Real Docker/PostgreSQL execution remains a human gate.


## Phase I.2 - Group application package organization

Suggested commit: `P2.2bc-I.2: Organize Group application capabilities by responsibility`.

- [x] Group application services, commands, ports and projections into cohesive `group`, `membership`, `invitation` and `access` packages. Keep shared failures and transaction outcomes at the application root.
- [x] Separate the group-root locking port from metadata mutation to avoid a package cycle. Retain the existing adapter, DI scopes, SQL, mutation protocol and public HTTP contracts.
- [x] Update imports and fixture wiring, add a production-bytecode architecture test for package cycles and root-to-feature dependencies, and reconcile the application architecture documentation.

**Self-review record:** The package dependency review identified a cycle if membership authorization continued to depend on the group metadata port; extract `GroupLockPort` while retaining one adapter. The initial compilation caught missing static test imports during relocation; restore those imports. No unresolved review findings.

**Checkpoint evidence:** Offline `mvn -B -ntp -o -Dmaven.repo.local=<isolated-cache> -Punit-tests -DtestParallelism=16 clean verify` passed 408 tests with zero failures, errors or skips, including Modulith and application package-cycle verification. Integration/E2E sources compile; PostgreSQL execution remains unavailable locally. No HTTP, SQL, migration, frontend or dependency changes. The I.1 real Compose smoke and final independent review remain maintainer gates.


## Phase I.3 - Post-merge Group E2E repair and organization

Suggested commit: `P2.2bc-I.3: Repair and organize Group E2E scenarios`.

- [x] Adopt the supplied post-P2.2a merge snapshot as the baseline; reproduce the eight test compilation errors.
- [x] Split application-service E2Es by group, membership and invitation responsibilities while retaining all 16 test methods. Extract shared stateless scenario operations and the holder-specific concurrency fixture.
- [x] Apply the merged lock-wait signature, holder-first resource cleanup and bounded SQL configuration consistently to every contention scenario.

**Self-review record:** P2 - Six membership and invitation concurrency tests declared the executor after the lock holder, risking worker shutdown waiting on a lock still held by the failing test; reverse the declarations and explicitly enable bounded statements for those scenarios. P3 - The invitation expiry test identified blocked sessions only by database and wait-event type; restrict the assertion to the one redemption blocked by the specific holder. Preserve the existing failed-synchronization regression and all scenario assertions.

**Checkpoint evidence:** The supplied post-merge POM differs from the offline handoff manifest (jOOQ codegen test dependency removed and opt-in Flyway migration plugin added); ordinary offline build dependencies still resolve without changing the POM. Offline `mvn -B -ntp -o -Dmaven.repo.local=<isolated-cache> -Punit-tests -DtestParallelism=16 clean verify` passes 405 tests with zero failures, errors or skips and compiles all Integration/E2E sources. All 16 original application E2E test methods remain discoverable by the `*E2E` naming convention. PostgreSQL execution remains a maintainer gate. Production behavior, persistence and HTTP contracts are unchanged.

## Phase I.4 - Member-scoped locking and migration E2E repair

Suggested commit: `P2.2bc-I.4: Scope group mutation locks to existing members and repair migration test`.

- [x] Address the carried-over [PR #36 finding](https://github.com/frederik-hoeft/min-favs-sharewise/pull/36#discussion_r4698383717) by requiring membership in the root-selection snapshot for member-scoped mutations. Retain the separate authoritative post-lock read, existing failure mapping, successful-path query counts and independent invitation-redemption lock capability.
- [x] Repair the invitation migration E2E's historical target and assert the intended pre-invitation schema before seeding data; validate the upgraded migration history without changing deployed migrations.
- [x] Align component fixtures and adapter tests with the distinct lock contracts, and add T21 E2E coverage demonstrating that a caller who belongs only to another group receives the same missing-group failure while the target root remains locked.

**Review record:** P3 - Registered nonmembers could contend on foreign group root locks: apply the correlated membership predicate and retain live post-lock authorization. P2 (test) - Invitation upgrade test still targeted Finance migration `202611241838` after Group core migration was moved to `202611251200`: target the actual historical Group schema and assert the preconditions before seeding. During self-review, strengthen the outsider scenario with an existing membership in another group so an accidentally uncorrelated membership predicate also fails the regression. Preserve bounded statements and holder-first cleanup.

**Checkpoint evidence:** Offline Maven `-Punit-tests -DtestParallelism=16 clean verify` passes 405 tests with zero failures, errors or skips, including Modulith verification and compilation of all Integration/E2E sources. Source encoding and Git whitespace checks pass. The migration repair follows directly from the checked-in Flyway version order; PostgreSQL execution of the repaired migration test and the new contention regression remains outstanding. No migration, generated sources, public HTTP contracts, frontend or dependencies changed.
