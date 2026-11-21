# P2.1.0 foundation: validation and review handoff

**Status:** Phases A–D approved; Phase E documentation and automated validation available for review. Independent security review, human acceptance and final merge remain pending. This is a task acceptance record, not the authoritative description of the running architecture.

The implemented system is described in [verified credentials and operation identity](../../../architecture/authorization.md) and [operation-scoped dependency injection](../../../architecture/dependency-injection.md). Remaining IAM login/session and Group-attestation designs stay in the [security design](../../../stack/security.md) and [domain-context proposal](../../../stack/claims-context-proposal.md).

## Verified automated evidence

On November 21, 2026, the Phase E security foundation, including the issuer-qualified primary-profile binding, issuance validation, and admission/HTTP follow-ups, passed the backend Unit profile using the supplied Linux x86-64 JDK 25.0.5.1, Maven 3.10.0, and the checksummed Maven-only dependency handoff. The command ran in `src/backend/sharewise` with an isolated Maven repository:

```bash
mvn -B -ntp -o -Punit-tests -Dmaven.repo.local=<isolated-maven-repository> clean verify
```

**Result:** `BUILD SUCCESS`; **186 tests run, 0 failures, 0 errors, 0 skipped**. The suite includes Spring Modulith published-interface verification and the existing DI, claims, JOSE and IAM ingress security tests. The security fixtures exercise verified and absent primary credentials, rejected or ambiguous credentials, scoped provider isolation, signing-key overlap/removal, issuer-side mandatory-field length limits and round trips, subordinate issuer/profile and session binding (including same-issuer substitution and explicitly trusted alternatives), independently timed subordinate admission, and authenticated non-HTTP workflow continuation without repeated expiry checks. The codec also tests symmetric per-profile size limits for signing and verification, including a signed JWT between 8 KiB and 16 KiB. HTTP/non-HTTP ingress composition is verified independently. A full-application-context MockMvc fixture also verifies the Boot-registered HTTP security chain, valid IAM bearer dispatch, anonymous behavior, protected-operation rejection, and invalid, expired, oversized or wrong-profile bearer rejection, and `/api` root matching. It supplies test signing keys and excludes database auto-configuration; transaction-segment identity continuity is exercised with stubs, not physical PostgreSQL segments.

The documentation review also verified 455 local Markdown links across 50 documents, with no broken local links or heading references. Changed Markdown files use UTF-8, LF and a final newline with no trailing whitespace; `git diff --check` passes. A pre-existing whitespace deviation in the untouched `docs/development/ai-development-workflow.md` remains outside this change.

## Acceptance boundaries

The automated Unit gate verifies the Spring Boot MVC/security integration with test keys and a mock servlet environment, not an actual deployed HTTP server or production key provisioning. It does not verify PostgreSQL-backed account/session validation, frontend authorization flows, or cross-service attestation issuance. PostgreSQL Integration/E2E and frontend gates were not run for this in-process security integration review. These limits do not convert planned workflows into accepted system behavior.

In particular, the API bearer path rejects missing mandatory trusted IAM wiring rather than operating with a fallback credential. Business operations must still require authenticated identity where necessary; the filter chain is not a general resource-policy enforcement layer. Temporal JWT validity is evaluated at external admission, not each internal operation dispatch. Continuations can reuse admitted evidence; any new external admission must verify its own credential, while authorization freshness for delayed work is business-owned.

The current HTTP chain is manually assembled from Spring Security web primitives. Migration to framework-managed `SecurityFilterChain` beans is an explicit **P2.1a prerequisite** before introducing cookie/CSRF-backed login and refresh endpoints, and requires a refreshed Spring Security configuration dependency handoff. This Unit gate does not validate that future wiring.

## Remaining human gates

- Review the Phase E steady-state architecture against the deployed/runtime configuration and verify it does not claim planned IAM or Group flows are implemented.
- Arrange the **independent security-sensitive code review** required by the P2.1.0 plan; this authoring and test pass is not a substitute for an independent reviewer.
- Perform applicable manual infrastructure acceptance when trusted IAM key provisioning and the relevant deployed HTTP path are available. Record the result separately from Unit tests.
- Approve and merge Phase E only after its review; authorize final P2.1.0 acceptance separately.
