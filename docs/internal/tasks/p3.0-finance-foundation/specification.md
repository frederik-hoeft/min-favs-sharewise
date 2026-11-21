# P3.0 - Finance schema and integration contract: proposed specification

**Status:** Draft for architecture/specification review, **not approved for implementation**. See the [Phase 3 technical ER and integration handoff](../../integration/phase-3-foundation.md), [domain model](../../../data-model-design.md) and [AI-assisted development workflow](../../../development/ai-development-workflow.md).

## Scope and architectural solution

Produce a shared, reviewable Finance Phase 3 **design** before P3.1–P3.4 author separate migrations and interfaces. Resolve overlapping schema ownership and the minimum Group→Finance event/snapshot contract; define where the work can proceed independently and the gates at which it must integrate. P3.0 adds no business implementation, SQL migrations, published Java APIs, Maven/npm dependencies or changes to the established financial domain model.

The proposed physical model has Finance-owned `financial_groups` (one row per initialized group; currency, lifecycle and finance revision) and `financial_actors` (retained composite `(groupId,userId)` key, active flag and source membership high-water mark). Only **Finance-local** FKs are allowed. Group Management owns a separately committed membership sequence and a published version-consistent current-state snapshot. Finance's projected membership activity and its financial revision counter have distinct meanings and transaction boundaries. P3.2 may use the actor's version as its deduplication invariant if and only if it has no other non-idempotent effect; journal and expense data are deferred to P4.

The [handoff](../../integration/phase-3-foundation.md) records an **on-demand explicit-currency initialization proposal**, including the required pre-initialization event-delivery/creation synchronization handshake and race-free initial snapshot and a coordinated writes-suspended migration option. The human reviewer must approve initialization timing/currency ownership, the safe treatment of events delivered before initialization, and the Group producer's authoritative source version before behavioral implementation starts. A hard-coded currency, direct runtime cross-schema backfill, unversioned events or a new generic framework is not an acceptable shortcut.

## Identifiable design requirements

| ID | Requirement and acceptance | Owner after approval |
| --- | --- | --- |
| F3-01 | Stable Group ID is an opaque `uuid`; Finance owns its tables and local FKs only; runtime operations use published Group contracts | P3.1/P3.2 |
| F3-02 | An initialized group has one explicitly selected accounting currency, a valid lifecycle, a finance revision initially `0`, and at least one retained actor; initialization is race-safe and currency-conflict safe | P3.1a/b; approval prerequisite |
| F3-03 | Finance actors have stable `(groupId,userId)` identity; leave deactivates and rejoin reactivates **without erasure** | P3.1a/P3.2a |
| F3-04 | Group membership changes and current-state snapshots have a committed, authoritative monotonic **source revision**; retry identities are stable; runtime consumers do not access Group SQL | P2.4b; P3.2a/b/c |
| F3-05 | Version-guarded upserts and reconciliation converge after duplicate, reordered, missing and concurrent events/snapshots; same-version contradictions fail visibly; markers and updates are atomic | P3.2a/b/c |
| F3-06 | Group-local Finance locking and transactional revision increments do not skip committed logical revisions on rejection/rollback or allocate duplicates under contention | P3.4; P4/P5 journal integration |
| F3-07 | Exact Money/Percentage and all three split strategies conserve totals, reject invalid input/overflow and break rounding ties deterministically | P3.3 |
| F3-08 | Specifications/tests preserve separate Unit, PostgreSQL Integration and durable-commit E2E evidence and do not mistake available fixtures for deployed features | Each owning task |

## Test design and acceptance matrix

| Case | Setup / action | Expected evidence | Gate |
| --- | --- | --- | --- |
| F3-T01 | Initialize twice concurrently with same/different currency choices | Exactly one Finance group; identical request idempotent or typed already-initialized result; conflicting currency rejected; no partial actors | P3.1b PostgreSQL E2E |
| F3-T02 | Seed a versioned membership snapshot and then leave/rejoin the same user | Actor PK unchanged, prior actor data retained, final `active=true`; no finance revision allocated | P3.2a/b Integration |
| F3-T03 | Deliver duplicate, stale and reversed events, including a contradictory event at the same actor/version | No stale overwrite; duplicates harmless; contradictory version rejected/diagnosed | P3.2a PostgreSQL Integration |
| F3-T04 | Apply snapshot at `R` concurrently with event `R+1`; omit a joined member's earlier event | Later event remains authoritative; reconciliation finds missing actor and deactivates absent former members without deleting them | P3.2b/c PostgreSQL E2E |
| F3-T04a | Deliver and acknowledge an event for an as-yet-uninitialized Finance group concurrently with a currency-initialization snapshot | Either event is durably retained for later application, or initialization is serialized with the absence check and its snapshot covers the event. No acknowledged event can be lost between snapshot and creation commit | P3.1b/P3.2c PostgreSQL E2E |
| F3-T05 | Crash/retry around registry delivery and Finance commit | Producer publication recoverable, consumer commit independent, repeated delivery has one final state, failed consumption remains retryable | P2.4c/P3.2c PostgreSQL E2E |
| F3-T06 | Exercise zero/negative, overflow, percentage totals, different currency scales, reordered participants, division remainders | Deterministic exact allocation sums, no floating-point drift, invalid inputs typed/rejected | P3.3 Unit/property tests |
| F3-T07 | Race group-local revision allocations; roll back one transaction; mutate two groups independently | Unique committed revision sequence within each group, rollback leaves no gap or committed artifact; no global serialization | P3.4 PostgreSQL E2E |
| F3-T08 | Examine Flyway SQL, FKs and Modulith dependency check | No cross-context FK/cascade/runtime SQL or unpublished cross-module type access | P3.1a Integration + Unit architecture |
| F3-T09 | Migrate pre-existing groups with writes suspended or initialize from a published snapshot | Every initialized group has the agreed currency and consistent membership watermark; no assumed historical event replay | P3.2b PostgreSQL E2E |

F3-T01–T09 are **future implementation acceptance cases**, not tests executed by P3.0. P3.0's own acceptance is a reviewed ER/schema ownership decision, source-event/snapshot semantics, a dependency-reduced task plan, link/diagram validity, and a documentation-only patch that leaves the baseline untouched.

## Decisions requiring human approval

1. **FinanceGroup creation/currency:** Prefer on-demand explicit currency selection with an atomic Group membership snapshot **and a proven serialized absent-group event-delivery handshake or durable pending watermark**. Alternative: automatic creation, requiring a specifically authorized source of currency and durable creation/bootstrap protocol. Select one before implementing P3.1b and initial synchronization. Neither currency choice nor group-accounting setup is defined in P2.0c.
2. **Group membership sequence:** Require a transactional group-level monotonic revision plus version-consistent Group-owned snapshot. Confirm P2.4b's ownership and changes to P2.2 persistence; choose full-state per-member event semantics so out-of-order versions can be skipped safely for that actor.
3. **Initial population and pre-initialization events:** Determine how events delivered for groups lacking a Finance row are handled without a snapshot/acknowledgment race, and whether existing Group records should be automatically enrolled in Finance at rollout. If yes, identify where each accounting currency comes from, pause Group writes for a coordinated migration, and seed source high-water marks; otherwise initialize only when users opt in.
4. **Consumer failure policy:** Define handling/diagnostics for impossible same-version contradictions, missing delivery and permanently failing publication records; do not silently mark incomplete synchronization successful.

These are **architecture-blocking questions**, not hidden defaults. P3.0 review may select alternative choices and update the proposed ER before any dependent migration is written. Inactive-actor participant eligibility, financial mutation authorship and FX remain later business-policy decisions, not blockers for pure math/schema design.

## Handoffs and review gate

P3.1a owns the common Finance DDL; P3.1b owns currency/configuration and initialization use case; P3.2a owns actor projection effects; P3.2b owns snapshot/backfill/reconciliation; P3.2c and P2.4b/c jointly accept real event delivery; P3.3 owns pure value objects/calculations; P3.4 owns group-scoped transaction/revision coordination. No duplicated interfaces or parallel migrations of `financial_groups` are created by those workstreams.

**P3.0 gate:** Human approves the target ER, the four decisions above, source version/snapshot and Finance initialization contracts, and the dependency/test matrix. Only then may the owning tasks finalize their feature specifications and begin implementation through their own reviewable phases. No completion boxes in P3.1–P3.4 are checked by accepting this document alone.
