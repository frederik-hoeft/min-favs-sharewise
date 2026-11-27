# P2.2f - Mandatory display names and user profile resolution: implementation plan

**Status:** Proposed sequential plan; awaiting maintainer approval of the [specification](specification.md) and this plan. No implementation or autonomous delegation is authorized by the planning patch. [Backlog](../backlog.md).

## Baseline and working agreement

The supplied `min-favs-sharewise-main-1127.zip` identifies upstream main revision `9a2222e213ad67b56d437664f8a7254901c3d318`. Its reconstructed tracked tree is `7cd1536561998d8271e8ca680d5b165838347568`; it includes the P2.5 distributed authorization implementation and its review follow-up. Create the task branch `task/p2.2f-user-profile-resolution` from that snapshot. Before implementation confirm the approved planning patch is integrated on the authoritative branch and check for intervening main/schema changes; do not silently rebase or reconstruct from a different snapshot. The planning-only checkpoint does not establish a green executable baseline.

Follow [AGENTS.md](../../../../AGENTS.md), the [AI workflow](../../../development/ai-development-workflow.md), the [ChatGPT workflow](../../../development/chatgpt-development-workflow.md), the [engineering-quality](../../../../.agents/skills/engineering-quality/SKILL.md) and [documentation](../../../../.agents/skills/documentation/SKILL.md) skills, and the [frontend instructions](../../../../src/frontend/AGENTS.md). Reopen the skills manually before each relevant phase, apply the review checklist, and read installed Next.js docs and Mantine references before editing UI. Preserve published module boundaries, existing transaction and session contracts and the P2.5 same-origin transport. No new dependencies or changes to unrelated Group/Finance schemas, JWTs or integration events.

Use the supplied offline toolchains/dependency archives; validate archive and descriptor hashes, restore without network resolution, and run the unchanged backend Unit/Modulith, frontend lint/typechecks/Vitest, normal/static builds and static-export probe before implementation. Record exact outcomes and handoff mismatches without changing descriptors to accommodate caches. Real PostgreSQL 18, migration tooling, jOOQ generation and deployed HTTPS may require a maintainer handoff. Use the existing [persistence workflow](../../../development.md#database), [test guide](../../../development/tests.md) and [deployment runbook](../../../../deploy/README.md). No generated sources may be fabricated or hand-edited. Each phase yields a focused committed diff with self-review, tests, predecessor/result trees and checksum; human acceptance is required before subsequent phases by default. Explicitly record any later autonomous opt-in and its mandatory stop points.

## Phase A - Required profile schema and migration

**Coverage:** UP-01/03, UP-T01. **Prerequisite:** approved spec/plan and verified unchanged baseline.

- [ ] Inspect existing Flyway version ordering and all account/profile creation paths, fixtures and schema expectations. Coordinate migration timestamp with parallel schema owners. Add a forward migration preserving existing valid names, filling null names and inserting missing profiles for every existing account, then enforcing `NOT NULL` and coherent CHECK constraints. Keep ownership/grants, IDs and all other data unchanged.
- [ ] Establish real PostgreSQL upgrade test from predecessor migrations with representative legacy accounts, nullable and absent profiles, duplicates and disabled accounts. Verify fresh install, constraints and migration atomicity; include protection against reinterpreting a user's existing name. Document writes-suspended coordinated rollout and the mixed-version limitation in the task evidence.
- [ ] Publish migration checkpoint for maintainer review and actual PostgreSQL/jOOQ handoff. Use real migrated PostgreSQL and the existing `identity` generation execution; obtain the reviewed generated diff, verify it matches the migration, integrate as a separate checkpoint and run schema freshness/compilation. No subsequent dependent implementation until the generated artifacts are accepted. Temporary incompatibility of the migration-only checkpoint with legacy registration is explicit; never deploy or merge it independently.

**Gate A:** Migration and generated mapping accepted; fresh and upgraded schema evidence recorded or a specific later PostgreSQL verification gate agreed without treating unrun tests as passed. The external code-generation gate is mandatory even under autonomous execution.

## Phase B - IAM lifecycle and profile API

**Coverage:** UP-01/02/04–07, UP-T02/03. **Prerequisite:** Gate A and accepted generated sources.

- [ ] Consolidate display-name validation into one mandatory immutable value; update registration command/factory/DTO independently of login credentials and own-account set/read contracts. Remove clear/null and first-write upsert semantics. Separate expected validation failures from data-integrity failures; preserve active-account locking and typed Result conventions.
- [ ] Create required profile within existing registration physical transaction before success; preserve authentication/session/event creation and uncertainty semantics. Add a narrow IAM-private bulk projection and operation for authenticated-public lookup. Validate the entire bounded raw batch before dispatch/query, deduplicate deterministically, return only IDs and names, distinguish unavailable from omitted IDs. Use one operation-oriented set query and no cross-context dependencies or new security profile.
- [ ] Migrate every backend test/fixture direct account insert, HTTP request, deployment smoke and test-only authorization fixture registration input to the new invariant. Preserve negative tests for unknown/null/clear fields as intentional rejections. Compile all test sources; exercise actual ingress and JSON contracts, atomic registration failure, update/disable concurrency, batch limits, duplicate names, absent/disabled targets and response minimization. Run full Unit/Modulith and affected real PostgreSQL Integration/E2E when available; retain required evidence for Gate E.

**Gate B:** Backend lifecycle and API contracts reviewed; coherent tests and fixture migration, no partial registration, no public email/security data, no stale nullable production API. Existing authz recovery semantics remain unchanged.

## Phase C - Registration, Account and shared profile resolver

**Coverage:** UP-08/09, UP-T04/05. **Prerequisite:** Gate B, approved API DTOs (frontend design/tests may use contract-conforming fixtures).

- [ ] Add distinct typed registration request through coordinator/adapter and form, required display name and local password confirmation; preserve login, invitation continuation, CSRF, unknown outcome and focus behavior. Remove clear operation and nullable handling from Account API/form; reuse confirmation for new password in the password-change form without changing its request. Update frontend and deployed fixture callers and test doubles together.
- [ ] Implement a small session-lifetime-bound profile resolver composed at the existing identity runtime root, exposed through authenticated capabilities. Reuse the shared client; keep cache separate from attestation handling. Bound positive and negative entries, deduplicate/chunk and coalesce overlapping requests, handle lifetime/cancellation and stale writes, and update/invalidate the own-profile entry after confirmed mutation. Avoid independent hook-level caches or a new framework. Use explicit TypeScript types in auth/transport paths.
- [ ] Test resolver policy with a deterministic clock and deferred responses: cold/warm, deduplication, chunking, overlapping waiters, bounds, negative/positive expiry, failure, stale response after own rename, disposal and session replacement. Test registration/account form local and server errors and unchanged login/password transport. Run Vitest, lint, both typechecks and normal/static builds and static-export probe.

**Gate C:** Reusable profile-resolution contract, mandatory profile UI and regression evidence accepted. A retained consumer cannot adopt a new login; ordinary domain UI does not manage authentication.

## Phase D - Shared user presentation and Group workspace

**Coverage:** UP-10/11/12, UP-T06/07. **Prerequisite:** Gate C.

- [ ] Design deterministic UUID color and suffix helpers with stable test vectors and collision extension for the complete relevant user set. Implement reusable accessible avatar/identity presentation using current Mantine/theme components. Use pure presentation inputs; never fetch from avatars or individual rows. Show own identity on Account, stable secondary ID in administrative/selection contexts and full ID details where useful.
- [ ] Batch-resolve Group workspace members at its composition boundary, propagate results to member rows, action labels and confirmation dialogs, and retain self markers and exact membership-ID mutation targets. Handle loading, missing and unavailable profiles without blocking Group operations; provide bounded retry and preserve responsive behavior. Remove raw UUIDs as ordinary primary labels, including accessible management labels. Keep Group REST DTOs and domain policy unchanged.
- [ ] Test identical names, deliberate color/suffix collisions, long/unusual names, fallback/retry, stale selection and exact mutation targets. Inspect actual UI at desktop/intermediate/mobile widths in both themes; use contract fixtures for visual work where the backend is unavailable, distinguishing that from deployed acceptance. Run all frontend gates and affected smoke contract tests. Supply reusable component/resolver usage examples for Finance at reconciliation, not Finance implementation.

**Gate D:** Presentation and integration reviewed; accessible and deterministic disambiguation independent of color, no UUID/key ambiguity, no N+1 HTTP resolution or page-level auth logic.

## Phase E - Aggregate verification and human acceptance

**Coverage:** all UP requirements and UP-T01–07. **Prerequisite:** Gates A–D.

- [ ] Self-review complete branch against specification and engineering checklist; correct findings and rerun affected tests. Verify baseline-relative diff, schema freshness, backend Unit/Modulith and PostgreSQL Integration/E2E profiles (actual tests executed), frontend lint/application and test typechecks/Vitest/normal and static build/static-export probe, deployment contracts and CI. Record exact commands, counts, revision and any environment gaps.
- [ ] Run clean disposable production-style HTTPS deployment smoke (`cd deploy && ./scripts/test-compose.sh`) and applicable opt-in browser fixture (`--browser`) against the integrated revision, including existing P2.5 E2Es with required profile input. Exercise migration of existing accounts separately from fresh-stack testing. Human browser acceptance follows UP-T07, including duplicate names, exact administration target, account rename and cache/session behavior; record actual results. No unavailable PostgreSQL/smoke check is accepted by inference; follow explicit workflow exception process only where permitted.
- [ ] Publish aggregate diff and acceptance evidence. Obtain maintainer acceptance of implemented behavior before authoritative documentation reconciliation. No backlog completion or merge.

**Gate E:** All required implementation evidence and human acceptance recorded; stop for missing prerequisites or material deviations. This gate remains mandatory if autonomous implementation is later authorized.

## Phase F - Documentation reconciliation, independent review and merge

**Prerequisite:** Gate E. No executable changes except separately reviewed corrections.

- [ ] Update authoritative profile/data-model and frontend architecture contracts, `docs/stack/security.md`, developer/API guidance, Phase 2 integration foundation, relevant Phase 4/5 UI handoff references, and deployment runbook. Reconcile obsolete optional-profile/clear/register statements, visibility and disabled-account semantics, API batch limits, cache ownership and reusable presentation contract; avoid duplicating the protocol or rewriting historical task specifications. Reconcile the authoritative ERD sources and rendered assets with mandatory names and exactly-one logical profile, coordinating any required external regeneration; distinguish application invariant from database-enforced cardinality. Preserve unrelated work and historical backlog entries. Document coordinated rollout, fallback-name migration and recovery limitations in the operational guide.
- [ ] Obtain independent security/architecture/code/test review against the same integrated revision; resolve or explicitly justify findings, rerun affected verification and manual acceptance, and update task-local acceptance record. Human approves documentation and explicitly authorizes merge; only then mark P2.2f complete according to the pre-merge workflow.

**Gate F:** Reviewed full diff, reconciled steady-state docs, accepted verification and explicit human merge authorization. Finance integration and P2.1e lifecycle remain separate.
