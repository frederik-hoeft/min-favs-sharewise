# P2.1.0 foundation: validation and review handoff

**Status:** P2.1.0 completed, independently reviewed, approved and incorporated into the current baseline. This acceptance record preserves the automated evidence and its limits; the deferred framework-managed HTTP-chain migration belongs to P2.1a, not to P2.1.0's completion gate.

The implemented system is described in [verified credentials and operation identity](../../../architecture/authorization.md) and [operation-scoped dependency injection](../../../architecture/dependency-injection.md). Remaining IAM login/session and Group-attestation designs stay in the [security design](../../../stack/security.md) and [domain-context proposal](../../../stack/claims-context-proposal.md).

## Verified automated evidence

On November 21, 2026, the Phase E security foundation, including the issuer-qualified primary-profile binding, issuance validation, and admission/HTTP follow-ups, passed the backend Unit profile using the supplied Linux x86-64 JDK 25.0.5.1, Maven 3.10.0, and the checksummed Maven-only dependency handoff. The command ran in `src/backend/sharewise` with an isolated Maven repository:

```bash
mvn -B -ntp -o -Punit-tests -Dmaven.repo.local=<isolated-maven-repository> clean verify
```

**Result:** `BUILD SUCCESS`; **187 tests run, 0 failures, 0 errors, 0 skipped**. The suite includes Spring Modulith published-interface verification and the existing DI, claims, JOSE and IAM ingress security tests. The security fixtures exercise verified and absent primary credentials, rejected or ambiguous credentials, scoped provider isolation, signing-key overlap/removal, issuer-side mandatory-field length limits and round trips, issuer-profile and subordinate-binding length compatibility, subordinate issuer/profile and session binding (including same-issuer substitution and explicitly trusted alternatives), independently timed subordinate admission, and authenticated non-HTTP workflow continuation without repeated expiry checks. The codec also tests symmetric per-profile size limits for signing and verification, including a signed JWT between 8 KiB and 16 KiB. HTTP/non-HTTP ingress composition is verified independently. A full-application-context MockMvc fixture also verifies the Boot-registered HTTP security chain, valid IAM bearer dispatch, anonymous behavior, protected-operation rejection, and invalid, expired, oversized or wrong-profile bearer rejection, and `/api` root matching. It supplies test signing keys and excludes database auto-configuration; transaction-segment identity continuity is exercised with stubs, not physical PostgreSQL segments.

The documentation review also verified 455 local Markdown links across 50 documents, with no broken local links or heading references. Changed Markdown files use UTF-8, LF and a final newline with no trailing whitespace; `git diff --check` passes. A pre-existing whitespace deviation in the untouched `docs/development/ai-development-workflow.md` remains outside this change.

## Acceptance boundaries

The automated Unit gate verifies the Spring Boot MVC/security integration with test keys and a mock servlet environment, not an actual deployed HTTP server or production key provisioning. It does not verify PostgreSQL-backed account/session validation, frontend authorization flows, or cross-service attestation issuance. PostgreSQL Integration/E2E and frontend gates were not run for this in-process security integration review. These limits do not convert planned workflows into accepted system behavior.

In particular, the API bearer path rejects missing mandatory trusted IAM wiring rather than operating with a fallback credential. Business operations must still require authenticated identity where necessary; the filter chain is not a general resource-policy enforcement layer. Temporal JWT validity is evaluated at external admission, not each internal operation dispatch. Continuations can reuse admitted evidence; any new external admission must verify its own credential, while authorization freshness for delayed work is business-owned.

The current HTTP chain is manually assembled from Spring Security web primitives. Migration to framework-managed `SecurityFilterChain` beans is an explicit **P2.1a prerequisite** before introducing cookie/CSRF-backed login and refresh endpoints, and requires a refreshed Spring Security configuration dependency handoff. This Unit gate does not validate that future wiring.

## Acceptance history and deferred integration gates

P2.1.0's independent review, human approval and baseline integration are complete. The earlier Unit evidence remains scoped to the shared claims/JOSE and ingress foundation, **not** a production IAM login or deployment acceptance.

P2.1a owns the next concrete gates: real IAM signing/profile and PBKDF2 credential adapters, migration to framework-managed HTTP security chains, CSRF/refresh-cookie endpoints and real PostgreSQL/browser acceptance. Production key provisioning, network deployment and Group attestations retain their own gates; none is implied by the approval of P2.1.0.
