# P2.2b/c - Implementation plan

**Status:** Approved by the maintainer on November 25, 2026 following the [specification](specification.md) gate. The [task-local workflow pilot](workflow.md) defines self-review, patch checkpoints and mandatory human stops. All boxes are initially unchecked; completion records distinguish agent-reviewed, human-accepted and infrastructure-unverified work.

## Baseline and common gates

Source snapshot `b1ec6649a9fa9d5f2cef1ceb41deddcf24b66c57`, local snapshot commit `09b66828c3d59724c20e284535b9820e813c6c42`. Offline baseline: 394 backend Unit tests, module architecture check, 296 frontend tests, lint, application/test TypeScript checks, Next build and static export verification passed. Supplied dependency and toolchain manifests match. PostgreSQL 18 and Docker are unavailable; Integration/E2E, generated schema freshness and Compose smoke require human execution. Chromium CDP is available; actual application screenshot harness still needs implementation outside production source. Preserve original baseline and publish ordered patch checkpoints.

For backend phases run supplied Maven offline `mvn -B -ntp -o -Dmaven.repo.local="$MAVEN_REPO" -Punit-tests clean verify`; compile real database tests and identify their outstanding execution. For frontend phases run `npm run lint`, `npm run typecheck`, `npm run test:typecheck`, `npm test`, `npm run build`, `npm run build:static` and `npm run test:static-export` with supplied offline tools/cache. Run targeted checks as needed and `git diff --check` at each checkpoint. Re-read engineering-quality each phase, self-review and iterate per workflow; record actual test counts, failures, screenshot evidence and patch predecessor/result.

## Phase A - Membership operations and workspace projection

Suggested commit: `P2.2b-A: Implement membership and role management`.

- [ ] Extend Group query and mutation ports/adapters using existing generated tables; coherent authorized workspace projection, scoped membership IDs, counts and stable ordering. Preserve metadata details and list endpoints.
- [ ] Add operation-scoped membership policies/services and typed failures; reuse admission, lock ordering, transactions and HTTP problem infrastructure. Implement role, remove/leave and atomic transfer endpoints. Preserve metadata version semantics.
- [ ] Design and implement T01–T06, including independent-connection race and rollback coverage, real SQL projection tests and HTTP denial/validation cases.
- [ ] Gate: backend Unit/module checks green; database tests compiled, unavailable execution explicitly recorded; critical self-review and incremental patch.

## Phase B - Configurable workspace shell and list

Suggested commit: `P2.2b-B: Establish responsive group workspace`.

- [ ] Reuse existing `ApplicationShell`; add declarative breadcrumb and optional aside configuration, shared authentication guard and group-specific nested composition without changing URLs. Check installed Next layout/route-group/static-export guides and Mantine reference; avoid effect-based slot registration.
- [ ] Add typed workspace API/resource, group-scoped lifetime and group navigation; no bearer coupling. Replace list cards with searchable rows and remove visible dummy navigation; show overview placeholder and roster/metadata in shared panel with Drawer fallback.
- [ ] Preserve existing editor/version handling. Evaluate breadcrumb disclosure versus explicit header control against narrow and nested navigation; retain accessible full-name behavior.
- [ ] Cover T13–T14 and affected API, shell, navigation and metadata regressions. Render live frontend with external mocked REST transport and inspect desktop/intermediate/mobile screenshots, long content and themes.
- [ ] Gate: frontend checks including static export and screenshot review; patch.

## Phase C - Member administration UI

Suggested commit: `P2.2b-C: Integrate member administration`.

- [ ] Integrate role change, transfer, remove and leave controls with clear confirmation, self marker, pending guards, stale target/permission and uncertain outcome reconciliation. Keep current group projection and breadcrumb coherent; preserve metadata draft behavior and session transparency.
- [ ] Cover T15, permission failures, simultaneous UI events, delayed results on route/session changes and keyboard/accessibility. Browser screenshot review for menus, dialogs and Drawer at relevant widths.
- [ ] Gate: frontend checks, focused review against GM/GW requirements, patch.

## Phase D - Shared opaque application token primitives

Suggested commit: `P2.2c-D: Extract reusable opaque application token primitives`.

- [ ] Extract technical generation, canonical parsing and verifier comparison from IAM into `shared::security`; use `ApplicationToken`, `ApplicationTokenGenerator` and DI-managed `ApplicationTokenParser`, redaction and defensive copies. Keep IAM session/cookie/storage policies and behavior intact. Register DI capabilities and declare Group module dependency when used; no cross-module internal imports.
- [ ] Cover T07 and run full backend Unit/module checks including IAM; compile affected IAM Integration/E2E and review actual call paths for format, replay and logging regressions.
- [ ] Gate: self-review security-sensitive compatibility; patch. No invitation schema dependency yet.

## Phase E - Invitation migration and jOOQ handoff

Suggested commit: `P2.2c-E: Define invitation persistence schema`.

- [ ] Create additive Group Flyway migration with ER-defined columns/domains/checks/index and least-privilege grants consistent with repository conventions. Add schema contract tests not requiring the as-yet-ungenerated mappings. No runtime invitation adapter depending on missing types.
- [ ] Run offline Unit/module gate; review migration/DDL and privileges; publish checkpoint with exact migration path, cumulative phase status and generation instructions. Explicitly record temporary schema freshness failure expected until generation.
- [ ] **STOP 1 (human):** Apply ordered patches, migrate the development database, run Group-only jOOQ code generation as documented in [development setup](../../../development.md), inspect generated diff, check CI and return the generated patch and feedback. Expected generator command from backend root in dev container: `./mvnw -Pjooq-codegen -Djooq.codegen.jdbc.url=jdbc:postgresql://postgres:5432/sharewise -Djooq.codegen.jdbc.user=sharewise_migrator org.jooq:jooq-codegen-maven:generate@groups` (with configured `JOOQ_CODEGEN_PASSWORD`). Migration must have run first; no source edits to bypass generation. Human may commit migration and generated sources together upstream if appropriate while retaining the ordered local checkpoint history.

## Phase F - Generated schema integration and invitation backend

Suggested commits: `P2.2c-F0: Integrate generated Group schema` (human-supplied diff checkpoint), `P2.2c-F: Implement secure shareable invitations`.

- [ ] Verify and apply maintainer-supplied generated patch against exact migration state, record hash/tree, run offline compile/Unit; publish integration checkpoint before dependent changes. Do not modify generated code manually.
- [ ] Implement invitation ports, adapters and operation-scoped services using Group root lock and shared opaque primitives; create/list/revoke/redeem HTTP contracts with uniform unavailable failures, bounded inputs, current expiry and atomic membership/count updates. Ensure no secret in diagnostics or noncreation projections.
- [ ] Cover T08–T12 including real commits, races and rollback. Audit all paths against current Group role/admission and existing IAM behavior. No new profile lookup, events or deletion path.
- [ ] Gate: backend Unit/module checks and compiled DB tests; self-review and patch.

## Phase G - Invitation frontend and continuation

Suggested commit: `P2.2c-G: Integrate invitation creation and acceptance workflows`.

- [ ] Admin list/create/revoke UI and copy-only-on-creation behavior. Typed GroupsApi additions, error and uncertain mutation reconciliation.
- [ ] Static `/invite` route and fragment capture; explicit user confirmation with authenticated transport. Add narrow safe navigation/auth continuation preserving login/register switch and no secret in return URLs or persistent storage. Preserve existing auth flows and session lifetime semantics.
- [ ] Cover T16–T18 and relevant auth regression cases. Render actual application with mocked REST for invitation and group flows at representative widths; inspect screenshot and accessibility behavior.
- [ ] Gate: frontend checks including static export, review security and state lifetimes, patch.

## Phase H - Aggregate review and acceptance readiness

Suggested commit: `P2.2bc-H: Harden and validate group workflows`.

- [ ] Review complete feature branch against specification, shared code reuse, transaction/authorization paths, concurrency and data ownership, async resource/route lifetimes, secrets, static deployment and test quality. Iterate fixes with tests; inspect complete UI at desktop/mobile/intermediate widths and long content. Run all available checks and report remaining database/Compose gates.
- [ ] Publish final implementation checkpoint, aggregate summary (including any deviations), ordered replay manifest, screenshot evidence and manual acceptance scenarios. Do not claim database or human acceptance before evidence.
- [ ] **STOP 2 (human):** Maintainer applies remaining patches, runs all real PostgreSQL suites/schema freshness and CI, production Compose smoke plus representative workflows, manual acceptance and aggregate review. Resolve findings with incremental follow-ups before documentation. Record tested revision and feedback.

## Phase I - Documentation reconciliation

Suggested commit: `P2.2bc-I: Reconcile group management documentation`.

- [ ] After human acceptance of implementation behavior, update authoritative architecture, persistence, Phase 2 handoff, security, frontend and development/testing guides as appropriate; preserve Phase 3 membership revision semantics. Record human validation results, limitations and any approved deviations in task artifacts.
- [ ] Check links/anchors, consistency and diff, rerun relevant checks and publish final docs patch. Independent reviewers review integrated revision; fix any accepted findings and reconcile docs again if behavior changes.
- [ ] Final maintainer merge authorization and backlog completion after all required reviews, acceptance and smoke evidence (or explicit documented exceptions). Keep `main` unchanged until approval.
