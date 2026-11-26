# P2.5 - Distributed authorization: implementation plan

**Status:** Planning draft; [specification](specification.md) and implementation sequence await human approval. All implementation checkpoints are unchecked. The default [human-gated workflow](../../../development/ai-development-workflow.md) applies; autonomous execution has not been authorized.

## Baseline and dependencies

Use the supplied source snapshot at upstream revision `3f34df3833cdadfc9b1330aa989f7c16919c1317` and accepted planning patch as the starting point. Commit and push approved task artifacts on the authoritative task branch before dependent implementation. Keep the approved local baseline unchanged until human authorization; each phase is a focused branch/checkpoint with a verified binary-capable Git patch, self-review and evidence. Changes to agreed architecture return to specification review.

The unchanged baseline was previously reported green for 406 backend Unit tests and 344 frontend tests, lint, both TypeScript checks, normal and static builds and static-export probe. The supplied Maven dependency handoff had a `pom.xml` manifest mismatch despite successful offline Unit verification; frontend descriptors matched. PostgreSQL Integration/E2E and Compose smoke were not run. The planning workspace was reconstructed from the same ZIP; this documentation-only patch does not rerun builds or re-establish the toolchain. Before code work, restore isolated verified JDK/Maven/Node and caches using the external offline and local-development skills; resolve or explicitly account for manifest mismatch and rerun the unchanged applicable gates in that workspace. Do not edit dependency descriptors to accommodate a stale handoff. No new dependencies, persistence schema or generated jOOQ sources are anticipated; any such requirement returns to review and, if needed, a refreshed handoff.

Read `AGENTS.md`, applicable repository skills, frontend `AGENTS.md`, [development tests](../../../development/tests.md) and [frontend guide](../../../development/frontend.md). Preserve published module boundaries, `allowedDependencies`, scope/transaction semantics, and existing IAM/Group business behavior. Phase 3 need not wait for P2.5; P4.5 integrates real downstream operations after the relevant P4 lanes and P2.5 contracts are ready.

## Phase A - Source-proof and correlation refactor (P2.5a)

**Prerequisite:** Approved specification/plan and unchanged baseline verification. **Coverage:** DA-01/02, DA-T01–03/16.

- [ ] Design the narrow non-forgeable candidate proof, format-neutral claims boundary where practical, and private immutable validation context. Preserve existing crypto and opaque token infrastructure; avoid exposing candidate evidence as application-admitted claims. Review exact package/API dependencies before migrating callers.
- [ ] Split `JwtCredentialCodec`/`JwtCredentialClaims` independent verification from mandatory cross-credential checks; preserve trusted profile metadata and consolidate binding in one implementation. Keep old proof methods fully bound while any caller remains.
- [ ] Update operation-scoped providers, `AuthorizationConfiguration` and DI correlation validators; migrate IAM ingress/initializer/provider and tests to preserve proofs. Ensure pre-effect admission, typed inputs, atomic publication, scope/lifetime validation, no repeated signature checks or dependency cycles. Preserve IAM early route admission and `IdentityContext` behavior; remove redundant transitional APIs after callers migrate.
- [ ] Add real crypto and scope tests for candidate vs admitted evidence, reversed provider order, missing/duplicate primary, trust mismatch, invalid/missing validators, provenance, scope cleanup and anonymous behavior. Run full Unit/Modulith and affected IAM HTTP tests.

**Gate A:** Review proof construction, mandatory correlation and module boundary together. All affected Unit and existing IAM regressions pass; no Group issuer or frontend change is necessary to accept this independently.

## Phase B - Supplementary ingress, semantic authorization and challenges (P2.5a)

**Prerequisite:** Gate A. **Coverage:** DA-03/04/06, DA-T01–05/09/15.

- [ ] Specify concrete header parsing/count/size bounds aligned with ingress, profile registry and safe rejected-credential discriminator. Implement generic DI-composed supplementary routing/verification; reject untrusted or ambiguous profiles and invalid supplied evidence. No Group-specific switch in shared transport. Introduce typed supplementary operation inputs and Group structural/consistency validation.
- [ ] Establish Group verification-only profile and cryptographically signed test fixtures; coordinate default IAM audience migration across app/deploy/config/tests. Preserve exact profile matching and trusted issuer-qualified binding.
- [ ] Publish organizational `GroupRole`, `GroupAuthorizationContext` and typed Result failure contracts; implement role/member/admin semantics and same-credential resource isolation. Reuse shared requirement descriptor and existing problem mapping.
- [ ] Define shared domain-principal/challenge DTOs, stable consumer-local requirement IDs, affinity policies, safe rejection metadata and human-readable detail. Add test-only consuming HTTP operation exercising pre-effect challenge, distinct requirement IDs and denial. Do not add a production route for this purpose or alter existing Group mutation policy.
- [ ] Test malformed/duplicate headers, unknown profiles, profile/key confusion, cross-group roles, conflicting assertions, pre-effect failures and absence of production fixture routes. Full backend Unit/Modulith verification.

**Gate B (P2.5a):** Human reviews ingress trust, failure contract, semantic facade, audience/config compatibility and test evidence. A real signed fixture can pass verification and semantic authorization; invalid evidence cannot reach protected effects. Issuance not yet required.

## Phase C - Group authority and issuance (P2.5b)

**Prerequisite:** Gate B. **Coverage:** DA-01/03/05, DA-T06–09/17.

- [ ] Implement shared authoritative Group membership lookup using existing transaction/root-lock ordering and caller admission; publish `GroupAuthorizationPort`, typed result and outside-active-segment guard. Preserve Group-owned current-state semantics; no implicit `IdentityPort` call or existing attestation prerequisite.
- [ ] Implement standardized `/api/groups/v1/domain-principal/attestations` adapter and issuer; derive subject/session from admitted primary; validate kind/scope, check current role, sign short-lived evidence with Group key. Handle denial/concealment, unavailable authority and non-cacheable response. No persistence migration.
- [ ] Configure independently provisioned Group keys and trust; reuse appropriate IAM-independent PEM/provisioning mechanics. Update deployment settings and key initialization with rotation overlap/removal and default 120-second lifetime; keep distinct private material.
- [ ] Add Unit and PostgreSQL Integration/E2E scenarios for authoritative verification, issuance after removal/downgrade, concurrency ordering, current primary vs IAM revocation staleness and rotation. Exercise actual issuance against test-only HTTP consumer. Run backend gates; obtain real PostgreSQL evidence before final acceptance (not inferred from Unit mocks).

**Gate C:** Review authority responsibility, transactions, signed contract and key configuration. Human reviews local evidence and schedules/runs unavailable PostgreSQL checks at the explicit aggregate gate; do not advance dependent work if a known contract or correctness defect remains.

## Phase D - Generic frontend handler/recovery pipeline (P2.5b)

**Prerequisite:** Reviewed challenge contract at Gate B; Gate C supplies real issuer before vertical testing. **Coverage:** DA-06/07, DA-T14–16.

- [ ] Refactor `lib/api/client.ts` singular pathname-only hooks to an ordered handler contract (supply the canonical full request target to affinity handlers) and transport-owned logical-request recovery context, preserving session-bound facade, request preparation, replayability, cancellation and exact original request. Keep domain adapter API stable where possible.
- [ ] Migrate `core/identity` hooks into IAM handler/composition; retain first-use restoration, stable lifetime, single-flight/Web Locks/CSRF, uncertain refresh handling and password-specific terminal denial. No IAM/domain protocol special cases in shared transport.
- [ ] Add bounded recovery accounting shared by original request and detours; distinguish primary-only credential acquisition path, pre-effect codes and terminal notification. Test cycles, repeated challenges, cancellation, stale generation and session replacement without requiring the real Group authority yet.
- [ ] Run existing and new Vitest suites, lint, application/test typechecks and normal/static builds as applicable.

**Gate D:** Human reviews generic boundaries and complete IAM regression behavior before adding another handler. No unbounded/nested recovery or business-specific frontend logic.

## Phase E - Generic attestation handler and two caches (P2.5b)

**Prerequisite:** Gates C/D. **Coverage:** DA-06–08, DA-T10–15/17.

- [ ] Extend `lib/api/api-error.ts` to retain validated authorization metadata; implement strict standardized challenge parsing, same-origin authority resolution and primary-only issuance adapter. Preserve detail and underlying acquisition failure; no hardcoded Group/Finance endpoint policy or per-domain handler. Validate bounded metadata/expiry and reject redirects/untrusted paths.
- [ ] Implement session-local CC with canonical descriptor key, opaque credential/expiry, per-key single-flight and generation-safe eviction; implement independent RC indexed by method/full URI projection, requirement ID and per-requirement policy. Use structured key encoding and bounded deterministic eviction; enforce header limits and deduplicate. Do not inspect body or execute policy code.
- [ ] Compose generic handler at application root; resolve RC and CC on dispatch, accumulate required credentials across bounded challenges, commit affinity on success, replace affected references on scope/policy change and preserve shared credentials. Lazy expiry renewal; no stale publication on session replacement or concurrent completion. Handle `never`, malformed hints, role denial and acquisition error without blind replay.
- [ ] Implement the central cache/handler scenarios DA-T10–15, including cold/warm/cross-endpoint, multi-authority independent policies, body/query projection, repeated/empty query parameters, changed scope and cache pressure. Exercise integration with IAM and signed backend HTTP fixtures; no production Finance adapter is required.
- [ ] Run frontend lint, both typechecks, tests, normal build, static build and static-export probe; run affected backend HTTP/Unit checks.

**Gate E:** Human reviews full handler flow, bounded recovery and cache identity/concurrency. Show both caches may be disabled without changing server-side authorization correctness, only round trips. Test-only consumer establishes protocol interoperability; do not claim live Finance business acceptance.

## Phase F - Aggregate integration and human acceptance

**Prerequisite:** Gates A–E. **Coverage:** all DA requirements and DA-T01–17.

- [ ] Self-review complete branch and call paths, resolve findings, and run all applicable regression gates. Record commands, revision, results and limitations. On isolated toolchains use `mvn -B -ntp -o -Dmaven.repo.local="$MAVEN_REPO" -Punit-tests clean verify` from `src/backend/sharewise`; use the corresponding PostgreSQL `integration-tests` and `e2e-tests` profiles when the service is available. Frontend from `src/frontend`: `npm ci --offline --include=optional --no-audit --no-fund`, `npm run lint`, `npm run typecheck`, `npm run test:typecheck`, `npm run test`, `npm run build`, `npm run build:static`, `npm run test:static-export` with the supplied Node/cache. Follow current development guide if invocation differs.
- [ ] Human runs real PostgreSQL Integration/E2E with committed downgrade/removal and ordering cases, IAM eligibility/refresh regressions and authoritative Group port. Verify both backend test profiles actually execute their intended tests. Review real crypto-backed HTTP round trip with effect-free rejected attempts.
- [ ] Extend/run `cd deploy && ./scripts/test-compose.sh` for actual configured Group signing, issuance and ingress forwarding/strict bounds as meaningful, keeping any downstream test-only consumer out of the production artifact. Record deployed revision and observed behavior; use the test-only HTTP harness for protected consumer replay that production cannot yet exercise. Human performs operational/manual acceptance of issuance, membership change, expiration/rotation and session replacement; no fictitious Finance UI acceptance. Unavailable smoke requires explicit documented human exception per workflow, not a green Unit substitute.
- [ ] Provide aggregate patch and evidence. Human approves implemented behavior before steady-state documentation reconciliation. No pending defect or missing prerequisite is treated as a passed gate.

**Gate F:** Human accepts implementation against specification, real service tests and applicable smoke/manual operational evidence. Actual Finance read/write authorization remains P4.5.

## Phase G - Steady-state documentation and final review

**Prerequisite:** Gate F. No executable changes except separately reviewed fixes.

- [ ] Reconcile `docs/architecture/authorization.md` with source-proof/correlation/publication, semantic contexts and ports; update `docs/stack/security.md` and `docs/stack/claims-context-proposal.md` so superseded provider-level binding, audience, issuance and challenge contracts are not left contradictory. Document authoritative ownership, point-in-time versus asynchronous checks and future endpoint family.
- [ ] Reconcile `docs/architecture/frontend.md`, `docs/development/frontend.md`, `docs/development.md` and affected deployment/config/API/test guidance; explain generic handlers, CC/RC, projections, failure/recovery and actual available integration. Search adjacent documents for stale single-replay or provider-binding language. Preserve historical task specifications and avoid rewriting existing backlog lines; link the new contracts where appropriate.
- [ ] Obtain independent security/architecture/code/test review against the same integrated revision; address substantive findings, revalidate and repeat affected manual acceptance/docs reconciliation. Record final review/acceptance evidence in a task-local `acceptance.md` as appropriate.
- [ ] Human authorizes merge; only then mark P2.5a/b complete according to the pre-merge workflow. P4.5 remains separately open.

**Gate G:** Complete reviewed diff, approved specification, independent review, required automated/real integration/deployment/manual evidence and reconciled documentation. Human merge authorization is mandatory.
