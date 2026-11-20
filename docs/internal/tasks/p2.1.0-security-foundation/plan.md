# P2.1.0 - Shared IAM security and claims foundation: implementation plan

**Status:** Specification and implementation sequence approved. Phases A–C have been approved and merged into the local baseline; Phase D is implemented on a feature branch and awaiting human review. Later phases remain subject to their individual human review gates under the [AI development workflow](../../../development/ai-development-workflow.md). Checkboxes track work only after implementation, validation **and** human review of the corresponding increment.

## Baseline, sequencing and dependencies

- Work from the approved supplied source baseline on separate task branches, following the local feature-branch/patch workflow. Do not merge planning artifacts or implementation work into the approved `main` baseline without authorization. Commit approved specification/plan to the authoritative task branch before dependent implementation begins.
- Preserve the implemented `shared::di` operation lifetime, `identity.IdentityContext`, current module boundaries, and P1.4 Spring/web/test infrastructure. Re-read `AGENTS.md`, the repository skills and the [test guide](../../../development/tests.md) before code changes.
- The original November 20, 2026 offline handoff selected **Maven** and lacked the Spring Security Resource Server/JOSE artifacts required by Phase C. The POM and dependency-only Maven handoff were refreshed, checksummed and verified before implementing Phase C; subsequent phases use that isolated cache. This environment has no Node/npm selection or assumed PostgreSQL service.
- Implementation phases are deliberately small and buildable. Each phase has one reviewable Git diff/patch, focused tests and a human gate. Review corrections remain on that phase's branch until approved; don't implement later phases merely because the preceding changes compile.
- Requirement IDs (`SF-*`) and acceptance IDs (`SF-T*`) refer to the [specification](specification.md#requirements-and-invariants). No phase may weaken a previously demonstrated trust or scope guarantee.

## Phase A - Generic operation input and scoped-configuration initialization

**Deliverable:** General-purpose extension of the existing `OperationDispatcher` in `shared::di`, with no JWT/domain-specific types.

- [x] Add a per-dispatch typed input registry with exact declared keys, validated non-null and duplicate registration behavior, optional read semantics, and an immutable/closed registration phase. Provide the new `dispatch(registerInputs, entryType, action)` overload while retaining the old API. Choose Java-idiomatic names and signatures in the change review, without treating the discussion's pseudocode as a rigid public API.
- [x] Define a narrow DI-composed initializer SPI with declared target/dependency types, `Optional<D>` input and ascending signed-integer priority. Resolve target through Spring, ensure it is actually operation-scoped, and reuse the same target instance across initializers. Do not register external objects in the `BeanFactory`, create another container, use a global current-input registry or expose inputs as a general application service locator.
- [x] Add a minimal designated scoped-configuration initialization/finalization lifecycle. Configuration must be unreadable before successful finalization and immutable afterward. Run initialization, validation/finalization and entry-service resolution/action in **one** `ServiceScope.run(...)`. Avoid eager construction of normal application services from initializers and avoid an unneeded dependency-graph scheduler.
- [x] Preserve recursive-dispatch rejection, owner-thread binding, single activation, nested scope isolation, deterministic cleanup and suppressed failure behavior. Verify register-before-scope failure leaves no allocated/active scope and exceptions inside an activated scope still close it.
- [x] Write focused Unit/component tests: same-target multi-initializer, priority incl. negative extreme, missing `Optional` inputs, explicitly required input, duplicate/null registration, wrong-lifetime initializer targets, early reads, post-freeze mutation, partial initialization failure, concurrency, nested dispatch and cleanup. Re-run the existing `OperationDispatcherTests`, `ServiceScopeTests` and architecture checks.

**Completion gate A (SF-01–SF-04; SF-T01–SF-T05):** The public extension remains small, scope behavior is unchanged outside explicit initialization, all focused and existing Unit tests pass with the original offline cache, and a human reviews the API, lifetime/ownership and error behavior. No security-specific initializer is introduced merely to demonstrate the mechanism.

## Phase B - Immutable provenance-preserving claims and provider composition

**Deliverable:** Source-agnostic `Claim`/`ClaimOrigin`/`AuthorizationCredential`, `AuthorizationCredentialProvider` and operation-scoped `AuthorizationContext` with no dependency on a particular JWT library, HTTP or IAM business model.

- [x] Define immutable claim and source metadata contracts appropriate to validated issuer/profile identity, audience, validity and optional token metadata. Each `Claim` carries its immutable `ClaimOrigin`, and `AuthorizationCredential` rejects mixed origins using record equality. Use defensively copied collections; preserve repeated claim kinds, distinct credentials and same-collection correlation. Avoid mutable claim properties, public trust-source creation shortcuts and a generic policy/query DSL.
- [x] Expose only the intentional named shared claims API. Implement Spring DI composition of **operation-scoped** providers and one immutable per-operation evidence snapshot. Providers use constructor-injected dependencies; no generic `CredentialInput` parameter on the `credentials()` API and no hidden database lookups.
- [x] Ensure provider failures abort snapshot construction, no partially accepted claims escape, and query methods return `Claim` with its origin preserved while full collection access retains per-credential grouping. A provider's explicit optional-absence result is different from invalid evidence. Avoid source-independent role unions or semantic authorization checks inside `AuthorizationContext`.
- [x] Implement isolated provider fixtures for independently verified evidence, duplicate claim names, multiple sets/resources, repeated claims and invalid provider behavior. Verify scopes have independent snapshots and no re-verification per query. Include negative module-boundary tests for claims internals.

**Completion gate B (SF-05–SF-06, SF-08 foundation; SF-T06–SF-T07):** No business policy or IAM/Group dependency has entered the shared claims API, all snapshot and module-boundary tests pass offline, and a human reviews provenance/query guarantees and the separation between verification and authority. A fixture that constructs evidence is test-only and not a production trust bypass.

## Phase C - Shared JWT/JOSE signing, verification and trusted token profiles

**Prerequisite: reviewed dependency selection and refreshed offline archive.** This is the first phase requiring Spring Security Resource Server/JOSE dependencies and real crypto-backed tests.

- [x] Select Spring Boot 4.1-managed Spring Security Resource Server and JOSE coordinates consistent with `docs/stack/security.md`, and adjust the backend POM without manually downgrading versions or importing unrelated authentication frameworks. On a networked preparation host, regenerate the selected Maven cache/checksum manifests; inspect the new handoff, extract to a pristine working copy and verify `mvn -o` can build the unchanged Phase B source plus new dependencies. Record exact versions and missing integration prerequisites.
- [x] Implement shared issuer/profile-specific JWT/JWS verification and signing primitives with an injectable clock, required claim typing and lifetime rules, configured audience/type/issuer validation and explicitly trusted key selection. Separate token decoding from proof of trust: no unverified claims may enter the public model.
- [x] Define accepted primary IAM access-token source mapping (`iss`, stable UUID `sub`, session `sid`, `aud`, `typ`, `exp`, applicable `iat`/`nbf`) without adding account/session persistence or issuing production login tokens. Implement narrowly bounded clock skew, algorithm allowlists, trusted key rotation overlap/removal and refusal of token-supplied key locations. Do not invent a key-discovery service or custom crypto algorithm.
- [x] Add a reusable source-verification/binding seam capable of validating a subordinate fixture against verified primary issuer/user/session **before** publishing a set. Keep actual Group provider, role/resource policies, attestation issuance and final wire spelling with P2.5a/b.
- [x] Exercise signing and verification with reproducible test keys and profiles. Cover invalid signatures, `none`/disallowed algorithm, unknown/removed keys, untrusted issuer, wrong audience/type, token-profile confusion, expiry/not-before/skew, malformed/missing subject/session and mismatched subordinate binding. No credential material enters logs or exception messages.

**Completion gate C (SF-07–SF-08; SF-T08–SF-T10):** Production source verification can distinguish trusted primary tokens from invalid/other-profile JWTs, cross-credential binding is demonstrably enforced for a synthetic subordinate source, crypto-backed Unit tests pass with the **new** offline handoff, and a human reviews trust configuration, key lifecycle, exception taxonomy and the POM/dependency change.

## Phase D - Trusted ingress, production IAM identity and boundary tests

**Deliverable:** Use the new generic dispatch inputs **once** at the external-ingress boundary; expose production `IdentityContext` via ordinary operation-scoped Spring DI.

- [ ] Add a composable caller-side DI contributor/dispatcher adapter and one dedicated operation-scoped security configuration with a minimal initializer mapping trusted external verification evidence into its immutable/finalized state. Do **not** seed `IdentityContext` or future `GroupAuthorizationContext` independently, or inject raw external objects directly as Spring-managed beans. Prevent retention of caller-mutable credential/claim state.
- [ ] Implement the IAM-owned operation-scoped production `IdentityContext` adapter over `AuthorizationContext`, preserving the existing interface. Select exactly one valid trusted primary IAM principal; absent optional primary input is anonymous, but invalid/ambiguous credentials, missing mandatory providers and invalid `sid` fail closed. Ensure the P2.0a test fixture remains opt-in and cannot mask missing production wiring.
- [ ] Add a narrowly scoped Spring Security primary-bearer verification/operation-binding integration path, with an ordered verification stage and explicit distinction between missing, invalid and authenticated credentials. Keep broader login/refresh/CSRF routes and user-facing auth flows outside this task. A non-HTTP adapter must be able to compose caller-side contributors and dispatch with a trusted primary input or with no primary input without referencing servlet/Spring request scope.
- [ ] Test the same production identity through two independently injected consumers across stubbed segment boundaries (and physical segments in E2E only when available), followed by sequential/concurrent operations with different authenticated and anonymous principals. Use a lightweight HTTP/MockMvc fixture for valid, absent, malformed, expired and wrong-profile bearer requests and validate appropriate authentication outcomes. Assert that invalid input never falls back to anonymous or to the test identity provider.
- [ ] Verify the actual Spring Modulith dependency graph, test-source component scanning, no reliance on `SecurityContextHolder` in semantic services, and no IAM dependency from `shared`. Run the full offline Unit profile; add database-backed tests only if actual SQL/physical transactions are introduced or used as part of a demonstrable integration invariant.

**Completion gate D (SF-09–SF-11; SF-T11–SF-T15):** The unchanged published `IdentityContext` works for verified and explicit anonymous operations through production wiring; rejected credentials cannot reach a protected action as authenticated or silently anonymous; scoped evidence never leaks; module and HTTP/component tests pass. Human reviews the ingress trust boundary and module contracts before approval.

## Phase E - Documentation reconciliation, full validation and acceptance handoff

- [ ] Update the authoritative [operation DI architecture](../../../architecture/dependency-injection.md) for the actual registry, initializer ordering, configuration freeze and dispatch lifecycle. Describe implementation invariants, not the task development history.
- [ ] Update the [security architecture](../../../stack/security.md) and [claims proposal](../../../stack/claims-context-proposal.md) to reflect implemented versus still proposed features, the exact verified-evidence publication boundary and the ownership of later Group-specific policies. Reconcile applicable [Phase 2 integration notes](../../integration/phase-2-foundation.md), `docs/development.md` and test/development guidance; avoid repeating the task spec in steady-state docs.
- [ ] Verify all Markdown relative links, code examples and terminology; run `git diff --check`. Inspect `git diff` against the approved baseline and confirm no unrelated files, generated build outputs, POM version workarounds or unrelated feature changes.
- [ ] Run the strongest available backend checks with the verified **supplied** JDK/Maven and fresh offline cache: `mvn -B -ntp -o -Punit-tests -Dmaven.repo.local=<isolated-repo> clean verify` and relevant focused selectors. If PostgreSQL is available and a database-relevant path was added, also run Integration/E2E profiles against the prescribed PostgreSQL 18 fixture. Do not call Maven restore a successful frontend build or a Unit gate proof of database behavior.
- [ ] Produce a clean review patch for the complete task (and incremental review fixes when requested). Present final acceptance evidence: legitimate verified IAM bearer, anonymous operation, rejected malformed/wrong-profile bearer, parallel principal isolation, signing/key-rotation fixtures and no accidental Group authority. Record any manual operational validation or limitations explicitly.

**Completion gate E (SF-01–SF-12):** Spec/plan and implementation review gates are approved; automated tests and architecture checks satisfy their intended boundaries; authoritative steady-state docs match the implemented system; the human performs the appropriate infrastructure/manual acceptance and separately authorizes final merge. An independent code-review pass is required for the completed security-sensitive change. The local feature branch stays unmerged until that authorization.

## Review boundaries and deferrals

| Owning follow-up | Deliberately excluded work |
| --- | --- |
| **P2.1a** | User registration/login, password verification, IAM session rows and initial real access-JWT issuance, refresh cookie and CSRF entry workflows. |
| **P2.1b** | Refresh-family rotation/reuse detection, revocation/logout, frontend retry and cross-tab coordination. |
| **P2.1d** | Authoritative current-account/session eligibility query and failure taxonomy. |
| **P2.2a–e / P2.4a** | Group-owned membership mutations and current-state checks, local user projection, durable IAM events and local-miss synchronization. |
| **P2.5a** | Real Group attestation verifier/provider, strict binding to current primary identity/session and resource/operation policy semantics. |
| **P2.5b** | Group attestation issuance and downstream protected flow. |

Phase C dependency refresh is an **external prerequisite**, not permission to alter a source dependency descriptor to fit an incomplete offline cache. Future credential sources can add source-specific verification/provider components without new dispatcher overloads, direct initialization of semantic contexts, or a general claim authorization engine.

If the implementation uncovers a material contradiction about trusted evidence exposure, operation configuration finalization, module ownership or the primary-identity contract, **stop and return to the specification gate** before replanning or writing dependent code. Minor Java API choices can be decided in the corresponding phase review while preserving the requirements above.
