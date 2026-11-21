# P3.0 - Finance foundation and integration contract: proposed specification

**Status:** Revised draft for architecture/specification review; **not approved for implementation**. See the [Phase 3 technical design handoff](../../integration/phase-3-foundation.md), [domain model](../../../data-model-design.md) and [AI-assisted development workflow](../../../development/ai-development-workflow.md).

## Scope and architectural solution

Define a single, reviewable Phase 3 **design** before P3.1–P3.4 independently implement persistence, actor projection, exact accounting and financial write coordination. P3.0 adds no Java/SQL implementation, dependencies, or deployed infrastructure.

The MVP uses one Finance-owned `finance.groups` (`FinancialGroup`) root per observed organizational Group, with retained `finance.actors` (`FinancialActor`) children and **Finance-local** foreign keys. A trusted membership event may provision the root in `PROVISIONING` without waiting for GroupCreated or currency configuration. An independent `GroupCreatedV1(eventId, groupId)` event ensures the root and activates `PROVISIONING → OPEN` **without providing owner/revision data**. Group Management publishes a separate versioned `GroupMemberAddedV1` for the initial owner in the same creation transaction (membership revision `1`). Delivery can occur in either order; `OPEN` does not mean the owner or all newer members are projected. Consumer writes, root creation and later financial operations serialize on the Finance root; financial revisions advance **only** for financial business mutations.

Group Management owns a separately committed, monotonic, **group-local membership revision** and full-state per-member events. Finance processes each member independently using that source revision; unknown departures create retained inactive tombstones. Group creation and delivery are asynchronous with independent transactions. No synchronous published snapshot lookup, cross-context transaction, orphan-actor projection, separate Finance queue, or continuous reconciliation is a Phase 3 requirement.

The MVP uses **one implicit, fixed two-decimal accounting denomination across all groups**. Neither a currency column nor a selectable currency is introduced in Finance DDL. Amounts are persisted exactly at the agreed fixed scale; the frontend displays one consistent symbol. A later currency-management vertical will explicitly add and initialize currency metadata by Flyway without reinterpreting or converting historical amounts. The exact displayed unit must be chosen before the first monetary write becomes available.

Existing pre-subscriber groups need not be supported automatically in the disposable MVP environment: a release must either reset its test/development data or perform a separately reviewed writes-suspended Flyway data backfill. Background drift-repair/reconciliation and periodic retries beyond the existing publication-registry contract are **deferred until operationally justified**. The [integration handoff](../../integration/phase-3-foundation.md) owns the detailed failure and ordering semantics.

## Identifiable design requirements

| ID | Requirement and acceptance | Owner after approval |
| --- | --- | --- |
| F3-01 | Finance alone owns `finance.groups` and `finance.actors`; `groups.id` is the upstream Group UUID, actor PK is `(group_id,user_id)` with a Finance-local FK, and neither Group nor IAM is referenced by a cross-context FK | P3.1a |
| F3-02 | Any trusted Group event ensures exactly one Finance root; only `GroupCreatedV1` transitions `PROVISIONING → OPEN`, independently of membership events. `OPEN` may temporarily have zero projected actors. Monetary mutations reject `PROVISIONING` without altering financial state | P3.1b/P3.2a |
| F3-03 | Actor identity `(group_id,user_id)` is stable through leave/rejoin; `deleted` retains missing-actor departure tombstones; `upstream_revision` guards transitions and membership replication never advances `current_revision` | P3.2a |
| F3-04 | Group creation atomically creates the owner and publishes independent `GroupCreatedV1(eventId,groupId)` and `GroupMemberAddedV1` (owner, revision `1`) facts. Later member add/remove facts carry stable IDs, resulting state and a transactionally incremented Group-local membership revision; GroupCreated carries neither owner nor revision. No runtime Finance access to Group-owned tables | P2.4b/P3.2b |
| F3-05 | Duplicate, reordered and concurrent actor events converge by **per-actor** version guards; contradictory same-actor/same-version payloads are errors. Neither a global highest-arrived watermark nor complete freshness is inferred | P3.2a/b |
| F3-06 | Finance uses the group-local root lock for provisioning, actor changes and financial mutations. Revision allocation is ordered/atomic under contention and never consumes a committed sequence position on rollback | P3.1b/P3.4 |
| F3-07 | MVP money and percentage arithmetic is exact at one fixed, documented two-decimal scale; equal/fixed/percentage splits conserve totals, deterministically resolve rounding and reject overflow/invalid input | P3.3 |
| F3-08 | Source authority, local projection and Group-created observation remain distinct: valid Group attestations may authorize Finance **reads** before the caller has an actor; actor-dependent writes require existing eligible actors, with no implicit creation. `OPEN` is not proof of membership freshness or authorization | P3.2/P2.5/P4/P5 |
| F3-09 | Failed event delivery remains retryable in the existing Modulith publication registry; no speculative periodic reconciliation, pre-subscriber import, Group deletion/tombstone, or currency management is added to the MVP | P2.4a/c, P3.2b |

## Test design and acceptance matrix

| Case | Setup / action | Expected evidence | Gate |
| --- | --- | --- | --- |
| F3-T01 | Deliver two distinct membership events for an unknown Group concurrently | One `PROVISIONING` Finance root; both actor states committed with proper Finance-local FKs; no duplicate group or financial revision | P3.1b/P3.2a PostgreSQL Integration |
| F3-T02 | Deliver `MemberAdded(B, rev 2)` before `GroupCreated(groupId)`, then deliver the separate initial-owner `MemberAdded(A, rev 1)` and replay all; repeat with GroupCreated arriving first | Finance preserves B; GroupCreated alone enters `OPEN` even if no owner actor exists yet; owner is inserted by its member event; replays are no-ops and all orders converge | P3.2a/b PostgreSQL E2E |
| F3-T03 | Deliver leave-before-join and ABA rejoin events in reverse order, including a previously unknown member's departure | Inactive tombstone is created; final highest source revision determines active state; stable actor identity and no stale resurrection | P3.2a Integration |
| F3-T04 | Deliver owner's later leave/rejoin before delayed initial-owner `MemberAdded(rev 1)` and GroupCreated; deliver contradictory payload at same actor/version | Stale owner event cannot overwrite newer membership; GroupCreated does not alter actors or reopen terminal lifecycle; contradictions fail observably; other users' lower revisions still apply | P3.2a PostgreSQL Integration |
| F3-T05 | Crash before/after Finance commit or registry completion; redeliver events and inject handler failures | Source transaction and publication are atomic; consumer transaction is independent; incomplete publication remains recoverable through established registry behavior; no second queue | P2.4c/P3.2b PostgreSQL E2E |
| F3-T06 | Exercise zero/negative/overflow, fixed two-decimal precision, percentages, input order and rounding remainders | Exact allocation sums, deterministic output and invalid-input rejection; no FX or varying-currency-scale support asserted | P3.3 Unit/property tests |
| F3-T07 | Race group-local financial mutations, roll back one, and mutate independent groups | Unique committed revisions, no rollback gap or cross-group serialization; actor delivery never increments `current_revision` | P3.4 PostgreSQL Integration/E2E |
| F3-T08 | Inspect Flyway DDL and Modulith module dependencies; attempt actor insertion with absent root directly | `finance.groups` / `finance.actors`, composite actor PK and local FK, `group_state`, nonnegative/positive bigint constraints and diagnostic timestamps; reject orphan actors; no currency field or cross-context FK | P3.1a Integration + architecture checks |
| F3-T09 | Attempt a financial mutation in `PROVISIONING`; with a verified Group attestation, read an `OPEN` group before the caller has an actor; attempt an actor-dependent write | Typed not-ready result in `PROVISIONING`; authorized read does not require an actor; write rejects missing actor without provisioning one; no Finance revision changes or unauthorized Group disclosure | P3.1b, P2.5 and later API/browser E2E |
| F3-T10 | Redeliver duplicate/stale membership events; race accepted newer events while transactions wait for the Finance root lock | No UPDATE/`updated_at` change for ignored events; `now()` may record transaction-start rather than acceptance/commit order; only `upstream_revision` decides ordering | P3.2a PostgreSQL Integration |

F3-T01–T10 are **future implementation test designs**, not P3.0 test results. P3.0 acceptance itself is human review of the shared invariant contract and test/dependency split, document consistency, and a documentation-only review patch.

## MVP decisions and deferrals

The following directions are selected **for this revised proposal**, subject to the P3.0 human review gate:

1. **Lazy root provisioning:** Any trusted Group-originated event can create `PROVISIONING`. The independent `GroupCreatedV1` opens the root without installing an owner; a separate initial owner membership event at source revision `1` is published in the same Group transaction. No interactive accounting setup is required. `OPEN` is not proof of actor replication/freshness or authority to access the Group.
2. **Source ordering:** Group Management begins its membership revision at `1` in the atomic Group/owner creation transaction and increments it on each later membership transition. A full-state per-member event uses that committed version, never membership UUIDv7 ordering.
3. **Currency:** One fixed implicit two-decimal accounting unit; no DB column or conversion. The concrete display code/symbol is finalized before the first expense/settlement feature. Introducing per-group currency later requires explicit, nonconverting Flyway migration and separate review.
4. **Rollout and recovery:** New data is created with the subscription installed; pre-existing groups are handled only by an explicit disposal/reseed or coordinated Flyway backfill at release. Initial registry delivery and its failure handling are mandatory, while periodic projection repair and scheduled retries are deferred to [operational hardening](../backlog.md#phase-8---operational-hardening-and-full-mvp-readiness).

Deferred business decisions remain: participant eligibility after leave, journal authorship/settlement permission, Group deletion/Finance retention (including delayed-event resurrection) and future currency support. P3 has no GroupDeleted consumer or deletion column. Do not activate a group from a membership event, assume an `OPEN` root has all actors, use replicated membership for API authority, or reinterpret stored monetary units by changing a display symbol.

## Handoffs and review gate

P3.1a owns the `finance.groups`/`finance.actors` DDL and its timestamp trigger. P3.1b owns idempotent root provisioning/locking and the lifecycle activation primitive. P3.2a owns guarded actor transitions and **independent** GroupCreated activation; P3.2b wires the real registry and Group producer with P2.4b/c. P3.3 owns pure fixed-unit accounting. P3.4 owns financial revision allocation on the shared root. No P3.2 snapshot/backfill task is a prerequisite for MVP acceptance.

**P3.0 gate:** Human reviews the column-by-column provenance and SQL constraints in the [Phase 3 handoff](../../integration/phase-3-foundation.md), the independent initial Group/owner events, lifecycle versus replication readiness, version/tombstone and timestamp behavior, attestation/actor separation, implicit-money-unit assumption, rollout/failure limitations, and acceptance matrix. Each owning task then needs its own approved specification/plan before implementation; no P3.1–P3.4 checkboxes are completed by these planning documents.
