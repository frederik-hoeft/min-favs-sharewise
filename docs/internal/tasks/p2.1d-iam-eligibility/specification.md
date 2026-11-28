# P2.1d - Authoritative IAM identity and session verification

**Status:** Approved for Phase A implementation. The [implementation plan](plan.md) was separately approved; implementation remains subject to phase review and final acceptance.

## Goal and MVP boundary

A verified primary IAM JWT establishes a caller's identity at ingress but does not establish that the associated account and session are still eligible in IAM's database. Group Management needs an authoritative lookup when an authenticated caller is missing from its local `groups.users` projection (P2.2e); later contexts may require the same stronger freshness for domain-critical operations. P2.1d supplies that synchronous IAM-owned Java capability without changing the existing credential-admission rules or requiring a network request in the modulith.

The MVP delivers one current-caller account **and** session verification operation, a local adapter, authoritative IAM persistence access and tests. It does not add an HTTP introspection endpoint, service-to-service authentication, a general-purpose introspection framework, global validation of every Group request, Group-side projection provisioning, or attestation issuance. Account deletion and cross-context lifecycle ordering remain P2.1e/P2.2e responsibilities. See the [backlog](../backlog.md), [planned security architecture](../../../stack/security.md) and [implemented identity architecture](../../../architecture/verified-credentials.md).

## Architecture and data flow

### Published synchronous capability

Use the semantic naming convention `<Concept>Context` for operation-local accepted evidence and `<Concept>Port` for a synchronous authoritative capability. Thus `IdentityContext` exposes a stable, previously verified caller/session snapshot, while the IAM-published `IdentityPort` obtains current IAM-owned state. A future `GroupAuthorizationContext` / `GroupAuthorizationPort` pair can follow the same distinction. Ports describe the *authority and possible synchronous failure*, not whether the adapter invokes Java or a remote service. The two interfaces remain independent; `IdentityPort` does not extend `IdentityContext` or make its snapshot methods perform implicit lookups.

Publish the contract in `de.hsh.favs.sharewise.identity`, the existing Spring Modulith API package. The following is the proposed minimal semantic shape; the named DTOs and failure cases are scoped to this task rather than copied literally from the backlog:

```java
public interface IdentityPort {
    Result<VerifiedIdentity, IdentityVerificationFailure> verifyCurrent();
}

public record VerifiedIdentity(UUID userId, UUID sessionId, Instant checkedAt) {}

public enum IdentityVerificationFailure {
    INELIGIBLE,
    UNAVAILABLE
}
```

The API uses the existing `shared.result.Result<T, E>` and immutable, non-null result values. On success, both IDs are those of the **same** verified primary credential and `checkedAt` identifies the IAM evaluation instant, not a validity lease. An `INELIGIBLE` error is an authoritative denial; `UNAVAILABLE` means no authoritative decision could be completed. Callers proceed with freshness-sensitive work only on `Ok`. An unauthenticated operation raises the existing `UnauthenticatedCallerException`, rather than returning an anonymous or negative database result. Invalid primary credentials fail at ingress and are never silently converted to anonymous state or an eligibility result.

`verifyCurrent()` takes no caller-supplied user ID, session ID or JWT. The local operation-scoped adapter obtains the immutable pair through `IdentityContext` and invokes IAM's application service. The service owns the account/session policy and its transaction; an IAM-private jOOQ adapter reads only IAM-owned tables. The implementation must not depend on HTTP request attributes, Spring Security context or consumers' persistence models. It does not re-verify the JWT signature or expiration inside the operation: the verifier already established credential validity at external admission. The current-state query answers a separate question about authoritative IAM facts *at call time*.

The in-process adapter is replaceable by a networked adapter without renaming the consumer-facing port or changing its results. A remote adapter can use a private request/response protocol carrying the necessary verified principal/session binding and authenticated service identity. User/session UUIDs are identifiers, **not** sufficient remote authentication proof; any future private IAM endpoint must authenticate and authorize the calling service and the represented caller under a reviewed protocol. Network timeouts and delivery failures map to `UNAVAILABLE`, not `INELIGIBLE`. No transport DTO or endpoint is designed in P2.1d.

### Authoritative eligibility decision

One read-only, database-side query over `identity.sessions` joined to `identity.users` returns the session ID and persisted creation, idle and absolute expiration timestamps only for a matching unrevoked session of an existing enabled account. IAM verifies that the returned session ID matches the admitted session; a mismatch is an invariant failure. After the read completes, IAM samples its injected `Clock` and evaluates `created_at <= now`, `idle_expires_at > now` and `absolute_expires_at > now`. The exact deadline is expired; a session that expires while the query is blocked cannot be accepted using a pre-read timestamp. A missing account, disabled account, missing/mismatched/revoked/expired session or future-created session yields the same `INELIGIBLE` result, without revealing which record is absent. The read neither acquires write locks nor mutates account, session or refresh-token state; it does not extend idle expiry or consume refresh credentials.

This check is deliberately stricter than checking account existence alone. It is independent of failed-login counters and temporary **login lockout**, which limit credential authentication but do not automatically revoke an already active session. It does not perform resource permission checks, inspect Group membership, require a refresh-token generation or authorize the consuming operation's own writes.

### Transactions, consistency and failure

IAM's application service executes the query in its own `Transactions.execute(...)` segment and requests `TransactionOutcome.abstain(...)`; the result is returned only after physical transaction completion. No consumer transaction may be active when invoking `IdentityPort`. In the current modulith, this uses the **same operation scope** and the existing transaction manager's support for sequential, non-overlapping segments; nested segments and unrelated ambient Spring transactions are rejected. There is no `REQUIRES_NEW`, secondary operation scope or loopback HTTP workaround.

A consumer that must read local state, call IAM and then mutate local state completes its initial read segment, invokes the port outside a transaction, then opens a separate transaction to re-read/lock and conditionally write. If intervening local changes matter, the consumer checks a stable version or equivalent concurrency invariant in the write segment; stale assumptions fail or retry through an explicitly reviewed policy. It does not hold a database lock while calling another authority. P2.2e owns its local-miss transaction sequencing and deletion/tombstone protection, not P2.1d.

The SQL statement observes one consistent database snapshot under the selected transaction isolation level. An `Ok` means the account and session satisfied the non-temporal eligibility conditions in that snapshot and the returned deadlines were valid when evaluated after the read. `checkedAt` records that evaluation instant. State may change after the read, including revocation before the result is returned; the check does not claim an atomic observation of both state and the application clock. The decision is neither a cross-context lock nor an atomic guarantee for a subsequent Group transaction. Future remote calls must preserve this same no-active-caller-transaction invariant even though HTTP would not itself detect nesting.

Known infrastructure failures preventing a reliable answer are reported as `UNAVAILABLE` where they can be safely classified. Unexpected defects and indeterminate transaction-finalization failures propagate and terminate the operation rather than being reinterpreted as authoritative denial or permitting later writes. Consumers fail closed for **all** errors and exceptions; an unavailable authority is not proof that an identity is absent. The check is read-only and safe to retry as a new call, but a consumer must not automatically retry its own non-idempotent mutation on uncertain failure.

## Requirements and invariants

| ID | Requirement |
| --- | --- |
| **IAM-D01** | Publish a narrow IAM-owned `IdentityPort.verifyCurrent()` with immutable typed result and failure contract; the existing `IdentityContext` remains a stable operation-local evidence snapshot and neither interface extends the other. |
| **IAM-D02** | Verify only the current caller's paired user/session from `IdentityContext`; reject anonymous operations, and do not accept arbitrary requester-supplied identifiers or reclassify invalid ingress credentials. |
| **IAM-D03** | Success requires existing enabled IAM account plus matching, unrevoked and unexpired session at the evaluation instant; expiration is exclusive. Login lockout alone does not invalidate a session. |
| **IAM-D04** | Use one read-only, IAM-private database query in an IAM-owned, physically completed `Transactions` segment; no schema changes, locks, refresh mutation or cross-context database access. |
| **IAM-D05** | Distinguish authoritative rejection from inability to decide, return success only after completed verification, and never allow unavailable or indeterminate outcomes to authorize work. |
| **IAM-D06** | Forbid synchronous authority-port calls inside an active consumer transaction; compose separate sequential segments and require consumers to revalidate mutable preconditions before conditional writes. |
| **IAM-D07** | The result is a point-in-time authority decision, not a lease or a promise of distributed atomicity; revocation after the snapshot is allowed under the agreed call-time validity model. |
| **IAM-D08** | Keep the Java API independent of Spring MVC, JWT/JOSE, SQL and transport DTOs; respect Spring Modulith published boundaries and allow a future authenticated remote adapter. |
| **IAM-D09** | Keep HTTP introspection, service credentials, Group fallback/projection mutation, P2.1e deletion handling, and P2.5 attestations outside the P2.1d deliverable. |

## Central tests and acceptance

| ID | Level | Scenario and required observation |
| --- | --- | --- |
| **IAM-DT01** | Unit | An admitted current user/session pair backed by an enabled account and valid session returns `Ok` with the same IDs and an evaluation time; no credential or refresh data is returned. |
| **IAM-DT02** | Unit + PostgreSQL Integration | Unknown/deleted-or-absent account, disabled account, absent session, mismatched owner and revoked session return indistinguishable authoritative denial; no state changes occur. |
| **IAM-DT03** | Unit + PostgreSQL Integration | At/beyond either idle or absolute deadline, or with future session creation, verification denies; one instant strictly within both deadlines succeeds. Login lockout without disablement remains eligible. A query blocked until a session deadline is reached must deny, and the returned session identifier must match the admitted session. |
| **IAM-DT04** | Unit + Modulith | Anonymous caller throws the existing authentication exception; misbound/invalid ingress cannot manufacture a current caller; only the published IAM API is accessible to consumer modules. |
| **IAM-DT05** | Unit + Integration | A local call works between completed consumer transaction segments; an invocation from an active segment or unrelated ambient Spring transaction is rejected, without hiding or corrupting transaction-manager state. |
| **IAM-DT06** | PostgreSQL Integration | Independent connections exercise lookup before and after committed logout, session revocation, account disablement and P2.1c password change: revoked *other* sessions are denied while the retained current session stays eligible. A concurrent commit after the read may not retroactively invalidate its result. |
| **IAM-DT07** | Unit + PostgreSQL Integration | Inject query/connectivity failure and relevant transaction-completion failure: never return `Ok` or misreport an outage as an absent account; a poisoned/uncertain transaction state cannot be followed by a consumer write. |
| **IAM-DT08** | Unit + architecture | Contract and local adapter use no HTTP transport, do not call JWT verification or refresh, start no nested transaction and introduce no migration or new build dependency. |

The behavioral and transaction tests must exercise actual PostgreSQL 18 for database semantics; mock-only tests cannot establish DB snapshot or concurrency correctness. P2.1d has no browser workflow or new HTTP route. Human acceptance is therefore an operational integration check through the published Java capability, complemented by real database and local deployment smoke evidence or an explicit approved exception under the [development workflow](../../../development/ai-development-workflow.md).

## Dependencies and review gates

P2.1b's session schema and lifecycle semantics are the accepted base. P2.1c is in progress; integrate its final retained-session/password-change behavior before accepting IAM-DT06. The P2.0a design-only example `CurrentCallerEligibility.check()` is superseded by this proposed concrete `IdentityPort` naming and `Result` contract upon approval. P2.2e consumes the published capability separately and defines the local projection race policy; P2.1e must settle account-deletion ordering before `UserDeleted` is applied. No Group implementation is needed to implement and Unit-test P2.1d.

The human approves this specification before separately approving the [plan](plan.md). Both approved artifacts must be committed and pushed to the authoritative task branch before code implementation. Each implementation phase is reviewed independently; the final gate includes real PostgreSQL verification, applicable security/module tests, steady-state documentation reconciliation, independent review appropriate for authentication-state correctness, and human acceptance before merge.
