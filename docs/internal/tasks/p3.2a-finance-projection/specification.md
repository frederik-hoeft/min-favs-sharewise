# P3.2a - Finance projection operations: specification

**Status:** Approved by the maintainer for P3.2a implementation; see the [implementation plan](plan.md). This task implements the Finance-side application operations of [P3.2a](../backlog.md#phase-3---finance-actor-synchronization-and-accounting-foundations) against the approved [Phase 3 foundation](../../integration/phase-3-foundation.md) (P3.0) and the implemented [P3.1 persistence foundation](../p3.1-finance-persistence/specification.md). Durable event delivery, listener registration and the Group-side producer belong to P3.2b, P2.4a and P2.4b.

## Goal and scope

Finance maintains a retained, source-versioned projection of organizational membership and activates its group root when Group Management reports group creation. P3.2a delivers the two Finance operations that apply those trusted Group-originated facts, so that every delivery order, duplicate and stale redelivery converges on the state implied by the highest source revision per actor. The operations are complete, independently testable units: they accept Finance-owned command values rather than Group event types, so they can be exercised with fixtures before P2.4b publishes real events.

### Included

- A Finance-internal, operation-scoped application service with two entry operations: **group activation** (the effect of `GroupCreatedV1`) and **membership change** (the effect of any full-state member event, including the initial owner's `GroupMemberAddedV1` at revision 1).
- Immutable Finance-owned command values for both facts and typed, non-failure outcomes describing whether the fact changed persistent state.
- A pure membership-transition policy that compares a received fact with the current actor state and selects insert, supersede, duplicate, stale or contradiction.
- A Finance-internal actor persistence port and jOOQ adapter (read one actor, insert, compare-and-set supersede), plus an activation operation on the existing `FinancialGroupStore`.
- Unit, PostgreSQL Integration and E2E tests specified below, and reconciliation of the steady-state Finance documentation.

### Excluded

- Spring Modulith listeners, the publication registry, event DTOs, event-to-command mapping, publication completion and redelivery behavior (P2.4a, P2.4b, P3.2b, P2.4c).
- Any HTTP API, frontend, authorization check, attestation handling or actor read projection for business operations (P2.5, P4, P5). The actor projection is never an authorization source.
- Schema changes or migrations. P3.1's `finance.groups` and `finance.actors` are sufficient; jOOQ metadata is unchanged.
- Financial revision allocation (P3.4), lifecycle transitions other than `provisioning → open` (P5.3), Group deletion, backfill, snapshot reconciliation, a transactional inbox or a pending-event queue.

## Architectural solution

### Module and package structure

All new types live in the closed `finance` module. Nothing is published to other modules; the P3.2b listener will reside in the same module and invoke the service through `OperationDispatcher`.

| Package | Responsibility |
| --- | --- |
| `finance.internal.projection` | Application service, command values, outcomes, the pure membership-transition policy and the contradiction exception. |
| `finance.internal.actor` | `FinancialActor` snapshot, `MembershipState` and the `FinancialActorStore` port, reusable by later Finance features that read actors. |
| `finance.internal.group` | Existing root port, extended with activation. |
| `finance.internal.persistence` | Existing root adapter, extended; new `JooqFinancialActorStore` adapter. Generated jOOQ types remain private here. |

The module's `allowedDependencies` grow by `shared::transactions` only, because the service owns its transaction segment. The policy and command types depend on nothing outside the JDK.

### Commands, outcomes and policy

The proposed shapes are below; implementation may adjust names within the same responsibilities.

```java
record ActivateFinancialGroup(UUID groupId) {}

record ApplyMembershipChange(UUID groupId, UUID userId, long membershipRevision, MembershipState state) {}

enum MembershipState { ACTIVE, REMOVED }

enum ActivationOutcome { ACTIVATED, UNCHANGED }

enum MembershipOutcome { INSERTED, SUPERSEDED, DUPLICATE, STALE }

record FinancialActor(UUID groupId, UUID userId, MembershipState state, long upstreamRevision) {}
```

Commands reject null fields and a nonpositive `membershipRevision` at construction. `MembershipState` is an explicit required value rather than a `deleted` boolean, so an omitted field in a future event mapping cannot silently mean "active"; the adapter maps it to the `deleted` column. Commands carry no event ID: the per-actor source revision is the idempotency key, and P3.2b can attach event identity to its own diagnostics.

The **membership-transition policy** is a pure function of the current actor (if any) and the received change:

| Current actor | Received revision vs. stored | Received state vs. stored | Decision | Persistent effect |
| --- | --- | --- | --- | --- |
| absent | any | any | `INSERT` | insert actor with received state and revision |
| present | greater | any | `SUPERSEDE` | update state and revision |
| present | equal | equal | `DUPLICATE` | none |
| present | equal | different | `CONTRADICTION` | none; fails the operation |
| present | less | any | `STALE` | none |

An unknown removal therefore inserts a `REMOVED` tombstone, and a later rejoin supersedes the same `(group_id, user_id)` row. The comparison is strictly per actor; no group-level watermark is read or written.

### Operation protocol

Each operation runs in **one transaction segment** owned by the service and acquires the Finance root lock before reading any mutable state. Both facts are trusted Group-originated facts, so both use `ensureAndLock`, which provisions an absent root.

```mermaid
sequenceDiagram
    participant L as Caller (P3.2b listener / test)
    participant S as Projection service
    participant G as FinancialGroupStore
    participant A as FinancialActorStore
    participant DB as PostgreSQL

    L->>S: applyMembershipChange(command)
    S->>DB: begin segment
    S->>G: ensureAndLock(groupId)
    G->>DB: INSERT root ON CONFLICT DO NOTHING; SELECT ... FOR UPDATE
    S->>A: find(groupId, userId)
    S->>S: policy decision
    alt INSERT / SUPERSEDE
        S->>A: insert / supersede(expected, next)
        S->>DB: commit
    else DUPLICATE / STALE
        S->>DB: abstain (no row changed)
    else CONTRADICTION
        S->>DB: roll back; throw
    end
    S-->>L: outcome
```

**Group activation** ensures and locks the root, then applies `provisioning → open` only if the locked snapshot is `PROVISIONING`, using a conditional update guarded by the expected state. A root that is already `open`, `closed` or `archived` is left untouched and reported as `UNCHANGED`. Activation never creates an actor and never changes `current_revision` or `created_at`. If the root was absent, the same segment inserts it as `provisioning` and opens it, so creation-before-owner yields an `open` root with zero actors.

**Membership change** ensures and locks the root, reads the actor, applies the policy and performs at most one actor write. It applies in every root lifecycle state: membership synchronization is organizational replication, not a financial business operation, and the P3.0 contract permits it on `provisioning` roots. It never changes the root's state or `current_revision`.

The **root lock is the serialization protocol** for all `finance.actors` reads and writes. The actor read therefore needs no row lock of its own. Holding the lock is a caller-enforced precondition: the actor store neither acquires nor verifies it, and the service satisfies it by calling `ensureAndLock` first. The adapter's guards detect specific persistence conflicts rather than lock ownership: insert relies on the primary and foreign keys, and supersede is a compare-and-set update whose `WHERE` clause requires the previously read state and revision and must affect exactly one row. An unexpected row count fails with an exception, not a typed outcome: the adapter throws `IllegalStateException`, which Spring's repository exception translation exposes to callers as `InvalidDataAccessApiUsageException` with the original as its cause. A write without the root lock still succeeds when no such conflict exists, so the guards complement rather than replace the protocol.

### Transaction decisions and failure semantics

| Result | Decision | Rationale |
| --- | --- | --- |
| `ACTIVATED`, `INSERTED`, `SUPERSEDED` | `COMMIT` | The operation changed persistent state, possibly including a newly provisioned root. |
| `UNCHANGED`, `DUPLICATE`, `STALE` | `ABSTAIN` | No row was changed: the root and, for membership, the actor already existed, and no UPDATE was issued. |
| Contradiction | exception, segment rolled back | A same-actor, same-revision fact with a different resulting state violates the producer contract. |
| Persistence or lock failure in the segment | exception, segment rolled back | The service neither catches nor maps it; callers see Spring's translated `DataAccessException`. |
| Commit or finalization failure | exception, outcome may be uncertain | Raised after the callback; the attempt may or may not have committed. |

The no-change outcomes are successful results: a redelivered or superseded fact has already been reflected, so P3.2b may complete its publication. They issue no actor UPDATE, so `updated_at` is unchanged.

A **contradiction is an exception** (a dedicated `ConflictingMembershipFactException`, carrying group ID, user ID, revision and both states) rather than a typed result. It represents a broken upstream invariant, not an expected business condition, and the exception makes the safe behavior the default: a caller that does not handle it cannot complete the publication, so the fact remains visible as a failed delivery instead of being acknowledged. The stored actor is never overwritten. Recovery is an operator decision; the operation performs no automatic repair or retry.

The service does not retry. Root and actor writes share the segment's physical transaction and commit or roll back together. A failure inside the segment callback rolls the segment back; once that rollback is confirmed, nothing of the attempt is committed, including a root inserted earlier in the same segment. An exception does not by itself prove that nothing committed: as the [transaction architecture](../../../architecture/transactions.md#completion-and-failure-behavior) describes, a failed commit attempt can leave the outcome uncertain, a failure after commit propagates without reversing durable changes, and a failed finalization can replace the callback's exception, which is retained as suppressed. Every exception means that no outcome is returned and the fact must not be acknowledged. Because both operations are idempotent with respect to committed state, the next delivery reprocesses the fact safely whether or not the failed attempt committed.

### Trust and authorization boundary

The service performs no caller authorization. Its inputs are trusted because only Finance's own inbound event adapter (P3.2b) will invoke it, and the module boundary prevents other modules or HTTP adapters from reaching it. P3.2a exposes no HTTP route or published interface. Finance business operations continue to authorize through Group attestations in later phases; neither actor existence nor `deleted` grants or denies request authority.

## Requirements and invariants

| ID | Requirement |
| --- | --- |
| PRJ-R01 | Group activation ensures and locks the root; if it is `PROVISIONING` it becomes `OPEN`, otherwise it is unchanged. `current_revision`, `created_at` and all actors are unchanged by activation. |
| PRJ-R02 | Activation on an absent root commits an `OPEN` root with revision zero and no actors. Repeated or delayed activation never reopens `CLOSED` or `ARCHIVED`. |
| PRJ-R03 | Membership change ensures and locks the root before reading or writing any actor of that group, in the same segment. A membership change on an unknown group commits a `PROVISIONING` root together with its actor. |
| PRJ-R04 | The persisted actor reflects the received state and revision exactly when the received revision is greater than the stored revision or no actor exists. A removal for an unknown actor inserts a `REMOVED` tombstone. |
| PRJ-R05 | Duplicate (equal revision and state) and stale (lower revision) facts execute no actor write; `deleted`, `upstream_revision`, `created_at` and `updated_at` are unchanged. The segment abstains. |
| PRJ-R06 | An equal revision with a different resulting state fails with `ConflictingMembershipFactException`, rolls back the segment and leaves the stored actor and root unchanged. |
| PRJ-R07 | Revision comparison is per actor. A higher revision for one actor never suppresses a lower revision for another actor of the same group. |
| PRJ-R08 | Neither operation changes `finance.groups.current_revision`, a root's lifecycle other than `provisioning → open`, or rows of other groups. |
| PRJ-R09 | Supersede is a compare-and-set on the previously read revision and must affect exactly one row; insert relies on the primary key. Violations raise an exception rather than a typed outcome; through the repository proxy, the adapter's `IllegalStateException` reaches callers as `InvalidDataAccessApiUsageException` with that cause. |
| PRJ-R10 | Commands reject null fields and nonpositive revisions; `Long.MAX_VALUE` is a valid revision. The state is an explicit enum value. |
| PRJ-R11 | Each operation uses exactly one segment; its decision follows the table above. Infrastructure failures propagate without being mapped to an outcome, and the service never retries. |
| PRJ-R12 | Operations for the same group serialize on the root lock; operations for different groups do not serialize on each other. |
| PRJ-R13 | The service, commands, policy and stores are Finance-internal. The module adds only `shared::transactions` to its dependencies; generated jOOQ types do not appear in ports. |

## Acceptance tests

Tests follow the [test architecture](../../../architecture/tests.md) and [test guide](../../../development/tests.md). PostgreSQL 18 is the authority for locking, constraints and trigger timestamps; no mocked SQL substitutes. Sequences of operations and concurrent operations need real commits and therefore run as E2E. Concurrency tests use independent operations and connections, coordinate through observable PostgreSQL lock waits rather than sleeps, use bounded waits and clean up on failure paths, following `JooqFinancialGroupStoreE2E`. "Final state" means a committed read from an independent application-role connection.

| Test | Requirements | Setup and action | Expected observation | Level |
| --- | --- | --- | --- | --- |
| PRJ-T01 | Policy | Evaluate every row of the policy table, including revisions 1 and `Long.MAX_VALUE` and both states. | Exact decision per row; no decision depends on anything but the two inputs. | Unit |
| PRJ-T02 | R10 | Construct commands with null fields, revision 0 and negative revision, and with `Long.MAX_VALUE`. | Invalid values rejected at construction; the maximum accepted. | Unit |
| PRJ-T03 | R03, R11 | Service with fake stores and a recording `Transactions` fake; run each policy branch and both activation branches. | Root is locked before the actor is read; at most one actor write; decision per outcome table; contradiction throws without a write. | Unit |
| PRJ-T04 | R01, R02 | Activate an absent root; activate seeded roots in each lifecycle at a nonzero revision with a known `created_at`. | Absent → committed `OPEN`, revision 0, no actors (`ACTIVATED`). `PROVISIONING` → `OPEN` with revision and `created_at` preserved. `OPEN`, `CLOSED`, `ARCHIVED` unchanged (`UNCHANGED`). | E2E |
| PRJ-T05 | R03, R04 | Apply `ACTIVE` rev 1 for an unknown group; apply `REMOVED` rev 2 for an unknown actor in an existing group. | One `PROVISIONING` root with an active actor at rev 1; a `REMOVED` tombstone at rev 2. Both `INSERTED`. | E2E |
| PRJ-T06 | R04, R05 | Seed actor `ACTIVE` rev 3. Apply rev 3 `ACTIVE`, rev 2 `REMOVED`, then rev 4 `REMOVED`. Record timestamps after each commit. | `DUPLICATE`, `STALE`, then `SUPERSEDED`; actor ends `REMOVED` rev 4. Timestamps unchanged after the duplicate and stale facts; `created_at` unchanged and `updated_at` reassigned after supersede. | E2E |
| PRJ-T07 | R06 | Seed actor `ACTIVE` rev 5; apply `REMOVED` rev 5. | `ConflictingMembershipFactException` with group, user and revision; actor and root unchanged from an independent connection. | E2E |
| PRJ-T08 | R04, R07 | ABA and reordering: for one actor, deliver add (1), remove (2), add (3) in every permutation, each fact as its own operation on a fresh database or group. | Every permutation ends `ACTIVE` rev 3, with one actor row. Leave-before-join (2 before 1) passes through a tombstone. | E2E |
| PRJ-T09 | R07 | Same group: deliver actor B rev 3, then actor A rev 2, then a stale A rev 1. | A ends at rev 2 and B at rev 3; B's higher revision did not suppress A. | E2E |
| PRJ-T10 | R01–R04 | Owner-late and owner-first: (a) activation, then owner `ACTIVE` rev 1; (b) owner rev 1, then activation. Also a later member (rev 2) before both. | Both orders converge on an `OPEN` root with the expected actors, revision 0; (a) passes through `OPEN` with zero actors; (b) passes through `PROVISIONING` with an actor. | E2E |
| PRJ-T11 | R03, R12 | Concurrent first observation: for a group with no root, start two membership operations for different actors and one activation concurrently. | Exactly one root, `OPEN`, revision 0, both actors present; no unique violation surfaces. | E2E |
| PRJ-T12 | R03, R04, R12 | Same-actor race: a fixture transaction holds the root lock; start operations for rev 1 `ACTIVE` and rev 2 `REMOVED` of the same actor, verify both wait on the lock, then release. Repeat with the opposite start order. | Final actor `REMOVED` rev 2 in both orders; one actor `INSERTED`, the other `SUPERSEDED` or `STALE` as order dictates. | E2E |
| PRJ-T13 | R12 | Hold group A's root lock in a fixture transaction while a membership change and an activation for group B run. | Group B's operations commit without waiting for A. | E2E |
| PRJ-T14 | R11 | Force a failure after the root was inserted and the actor written in the same segment (for example a test-only failing collaborator or a constraint violation in a fixture), for an unknown group. Then reprocess the same fact. | Nothing committed by the failed attempt; the retry commits root and actor once. | E2E |
| PRJ-T15 | R08 | Run every operation outcome against a root with nonzero `current_revision`. | `current_revision` unchanged in all cases; other groups' rows unchanged. | E2E |
| PRJ-T16 | R09 | In a rollback segment, call `supersede` with an expected revision that does not match the stored row, and `insert` for an existing actor. | Supersede raises `InvalidDataAccessApiUsageException` caused by `IllegalStateException`; insert surfaces the persistence exception as `DuplicateKeyException`. Adapter maps `MembershipState` to `deleted` both ways. | Integration |
| PRJ-T17 | R13 | Existing Modulith verification. | Finance remains closed; only the declared dependencies are used; ports expose no generated types. | Unit |
| PRJ-T18 | R03, R04, R08 | Seed a root in each lifecycle state at a nonzero revision; apply an unknown actor, a newer fact, a duplicate and a stale fact. | `INSERTED`, `SUPERSEDED`, `DUPLICATE`, `STALE` in every state; actor reflects the highest revision; the complete root row is unchanged throughout. | E2E |
| PRJ-T19 | R04, R12 | Start the higher revision's transaction for a seeded actor, then let a later transaction apply and commit a lower revision before the first acquires the root lock and applies its fact. | The higher revision ends authoritative with `updated_at` equal to its earlier transaction start and before the lower revision's committed `updated_at`; `created_at` and the root row are unchanged. | E2E |

The implementation adds further Unit and Integration tests for adapter mapping, null handling and other technical detail as needed. Tests do not add production entry points.

### Acceptance gate

P3.2a is accepted when the operations meet the requirements above, the Unit, Integration and E2E suites pass against PostgreSQL in the human development environment or CI, independent review findings are resolved, and the steady-state documentation describes the implemented operations. P3.2a has no user-facing workflow and registers no listener, so there is no meaningful manual end-to-end path; acceptance evidence is the PostgreSQL E2E suite plus a disposable deployment smoke run confirming that startup and migrations are unaffected. Record the tested revision, commands, outcomes and limitations. Passing the offline Unit gate alone does not establish acceptance.

## Decisions

- **Finance-owned commands instead of Group event types.** The operations are testable before P2.4b exists, Finance does not couple its application layer to another module's DTOs, and P3.2b owns the one-place mapping. The cost is a small mapping in P3.2b.
- **Read-decide-write under the root lock instead of a single conditional upsert.** The foundation's `INSERT ... ON CONFLICT DO UPDATE ... WHERE` cannot distinguish duplicates from contradictions and would still need a second read. Keeping the comparison in a pure policy makes every branch unit-testable and keeps business policy out of SQL; the root lock already serializes all actor writes, and the compare-and-set update rejects a supersede derived from an actor that has changed since it was read. Root-lock ownership remains a caller precondition rather than a runtime check; verifying it would need segment-bound lock tracking that no current caller requires. The cost is one extra statement per fact.
- **Activation reuses `ensureAndLock`** rather than inserting an `open` root directly, so P3.1's provisioning primitive remains the only root-creation path. An absent root costs one insert and one update.
- **No inbox.** The per-actor source revision and the root lifecycle guard provide idempotency for duplicate, delayed and reordered facts, as approved in P3.0 and the [messaging design](../../../stack/messaging.md#consumption-and-idempotency).
- **Contradiction raises an exception** (see failure semantics). The alternative, a typed `CONTRADICTION` outcome with rollback, would require P3.2b to remember to convert it into a failed delivery.

## Open questions

- **Membership changes on `archived` roots.** P3.2a applies them in every lifecycle state. P5.3 introduces archival and should confirm whether a terminal root still accepts replication or ignores it; no archived roots can exist before then.
- **Same revision on different actors.** Group revisions are group-wide, so two actors with the same revision would also indicate a producer defect. Detecting it would need a group-level revision check or index; P3.2a does not detect it, consistent with the per-actor P3.0 contract.

## Dependencies and handoffs

| Boundary | Handoff |
| --- | --- |
| **P3.1 → P3.2a** | Uses `FinancialGroupStore.ensureAndLock` unchanged and extends the same port with activation. No second lock primitive. |
| **P3.2a → P3.2b** | The service and commands are the consumer contract: P3.2b maps `GroupCreatedV1` to `ActivateFinancialGroup` and every member event to `ApplyMembershipChange`, dispatches one operation per event, completes the publication after a returned outcome and lets exceptions fail the delivery. |
| **P3.2a → P3.4 / P4** | Future financial writes acquire the same root lock first; P3.2a never allocates a financial revision. `FinancialActorStore` can be extended with the eligibility reads P4 needs. |

## Documentation reconciliation

The final implementation step updates the [persistence architecture](../../../architecture/persistence.md#finance-persistence) with the projection operations, activation and lock ordering; the module summary in the [architecture overview](../../../architecture.md); and the status line of the [domain model](../../../data-model-design.md). The [Phase 3 foundation](../../integration/phase-3-foundation.md) changes only if review alters a shared contract, which would return to the P3.0 gate.
