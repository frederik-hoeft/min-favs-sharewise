# Phase 3 design handoff: Finance foundations

**Status:** Approved shared Phase 3 contract (P3.0). This design defines the target model and integration semantics, not the implementation or deployment status of every task. This document owns the **cross-team model, invariants and integration guarantees**; the [backlog](../tasks/backlog.md#phase-3---finance-actor-synchronization-and-accounting-foundations) owns sequencing, task boundaries and acceptance gates. Read it with the [domain model](../../data-model-design.md), [Phase 2 identity/Group handoff](phase-2-foundation.md), [persistence foundation](../../architecture/persistence.md), [messaging architecture](../../stack/messaging.md) and [security design](../../stack/security.md).

**Audience:** Independent owners of Finance schema/provisioning (P3.1), membership projection (P3.2), pure accounting (P3.3), financial revision coordination (P3.4), and the Group-side event producer (P2.4b). This handoff specifies **only the two Phase 3 Finance tables** and their Group-side prerequisites; Expenses, Settlements and ChangeRecords belong to later phases.

## Sources, provenance and scope

The schema reconciles requirements in this order. An earlier domain requirement remains valid unless a more authoritative decision explicitly narrows it for the MVP.

| Marker | Source / authority | Used for |
| --- | --- | --- |
| **DEC** | Agreed design direction captured in the [Phase 3 backlog](../tasks/backlog.md); approved under P3.0 | MVP lifecycle, event separation, actor replication, revision rules, no currency/deletion feature |
| **DOM** | [Conceptual domain model](../../data-model-design.md) | FinancialGroup aggregate boundary, retained actor identities, financial revisions and authorization separation |
| **ER** | [Authoritative yEd ERD](../../assets/er-data-model.pdf) | Table/column names, composite keys, defaults, enum and type choices |
| **REC** | Reconciliation and implementation rationale in this handoff | Concurrency protocol, diagnostics and future integration constraints |
| **PG** | PostgreSQL implementation mapping | `uuid`, `timestamptz`, `bigint` checks/domains, PK/FK/index/trigger semantics |

**P2/ARCH** denotes constraints from the [Phase 2 ER handoff](phase-2-foundation.md), [architecture](../../architecture.md), [persistence](../../architecture/persistence.md), [messaging](../../stack/messaging.md) and [security](../../stack/security.md). The Group Management ER remains owned by P2; only the `membership_revision` producer requirement is specified here.

### Ownership and logical relationships

Group Management owns `groups.groups` and `groups.group_members`, authoritative membership, roles and Group-issued attestations. Finance owns `finance.groups` and `finance.actors`, its lifecycle and its **separate financial change sequence**. A Finance root may exist before the Group-created event arrives. Every actor belongs to one Finance root, including inactive tombstones; no separately persisted orphan projection is required.

The [authoritative yEd ERD](../../assets/er-data-model.pdf) defines the visual entity relationships and physical table structure. The column-level provenance and cross-team contracts below explain those design choices without reproducing the diagram.

Organizational Group-to-Finance relationships are **logical, never PostgreSQL FKs**; `groups.group_members` replicates into Finance through versioned events rather than a cardinality-equivalent table relationship. Organizational Group/member IDs are not cross-context database constraints even though all Modulith schemas currently share one PostgreSQL database. `finance.groups` has `0..*` actors; `OPEN` alone does not require an actor row or guarantee the latest membership projection. There is no Finance-local `user_id`-only index in P3: expected reads and events are group-scoped, supported by the leftmost `group_id` portion of the actor PK.

## P3 schema reference: `finance.groups`

One row per organizational Group that Finance has observed through a **trusted** Group-originated event. The row is the aggregate root, source of the Finance lifecycle, and the shared **group-local row-lock target** for projection changes and future financial writes.

| Column | PostgreSQL type and constraints | Provenance / why it exists |
| --- | --- | --- |
| `id` | `uuid PRIMARY KEY`; **no generated UUID default** | **DEC, DOM, ER:** Exactly the upstream `groups.groups.id`, rather than an independently generated Finance identity. Prevents multiple Finance roots for one organizational Group. No cross-context FK. |
| `state` | `finance.group_state NOT NULL DEFAULT 'provisioning'` (enum: `provisioning`, `open`, `closed`, `archived`) | **DEC, DOM, ER:** Business lifecycle. A member event may create a `provisioning` root; only `GroupCreatedV1` activates it to `open`. Closed/reopen and terminal archive are later P5.3 business operations. |
| `current_revision` | `finance.bignatural0 NOT NULL DEFAULT 0` | **DOM, DEC, ER, PG:** Position in Finance's group-local **financial business-change** sequence; initialized at zero. Membership events and initial Group-created activation do not advance it. P3.4 owns atomic allocation for future money/history mutations. |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | **ER, REC, PG:** Diagnostic timestamp for when Finance first persisted this root, **not** organizational Group creation or transition to `open`. A late Group-created event does not change it. |

The PostgreSQL enum is the current **ER-selected** representation, not a requirement to share a type across bounded contexts. Its values and lifecycle guards must be tested against the real migrated database. `provisioning` rejects financial mutations; `open` permits them subject to authorization and actor eligibility. Closing/reopening and archival are not implemented in P3, although the schema reserves their states. No currency, readiness watermark, `opened_at`, `deleted_at` or financial journal column is required by P3.

## P3 schema reference: `finance.actors`

One retained financial identity for each `(group_id, user_id)` across the entire lifetime of that membership's join/leave/rejoin cycles. A removal changes a flag; it never deletes the actor row, so later Finance records can refer to the same identity.

| Column | PostgreSQL type and constraints | Provenance / why it exists |
| --- | --- | --- |
| `group_id` | `uuid NOT NULL`; first column of `PRIMARY KEY (group_id, user_id)`; `FOREIGN KEY (group_id) REFERENCES finance.groups(id)` | **DEC, DOM, ER, PG:** Finance-local aggregate membership and referential integrity. The group must be ensured in the same Finance transaction before inserting the actor. No cascade deletion. |
| `user_id` | `uuid NOT NULL`; second column of composite PK | **DEC, DOM, ER:** Stable IAM user identity, **not** the Group membership row UUIDv7. No IAM FK. Uniqueness per group is enforced by the PK; no surrogate actor UUID or redundant unique index. |
| `deleted` | `boolean NOT NULL`, **no default** | **DEC, DOM, ER:** Last accepted resulting membership state (`false` = active, `true` = removed). An unknown removal creates an inactive tombstone. No implicit activation from an omitted event field. |
| `upstream_revision` | `finance.bignatural1 NOT NULL` | **DEC, ER, PG:** Last committed **Group-owned membership revision affecting this actor**, used for per-actor version guards and ABA/reordering safety. Not a Finance revision and not derived from UUIDv7/timestamps. |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | **ER, REC:** Finance-side first persistence, including a tombstone created before its join arrives. It is not the Group membership's `joined_at`. |
| `updated_at` | `timestamptz NOT NULL DEFAULT now()`; `BEFORE UPDATE` trigger assigns `now()` | **ER, REC, PG:** Diagnostic timestamp of an accepted local projection update, not event occurrence/commit order. Only actual newer-version changes should issue SQL `UPDATE`. |

The composite PK also lets later Finance tables reference a payer, participant or sender with `(group_id, user_id)` FKs that enforce same-FinancialGroup membership **without extra cross-table constraints**. Those tables/FKs are **not** part of P3. A separate `user_id` index is deferred until a concrete user-centric, cross-group Finance query exists; PostgreSQL's `(group_id, user_id)` B-tree already serves the group-scoped access patterns.

### Numeric and timestamp semantics

`bignatural0` and `bignatural1` in the yEd diagram mean signed PostgreSQL `int8`/`bigint` constrained respectively to `>= 0` and `> 0`. Finance uses the Finance-owned PostgreSQL domains `finance.bignatural0` and `finance.bignatural1`, with `CHECK (VALUE >= 0)` and `CHECK (VALUE > 0)` respectively. Group Management owns its corresponding types independently; no cross-context type dependency is introduced.

`now()` is PostgreSQL's **transaction-start timestamp**. This is acceptable for the diagnostic `created_at` and `updated_at` columns selected by the ER: a transaction that waits for a row lock may commit a newer projection revision with an *earlier* `updated_at` than a prior commit. Neither column establishes source order, commit order or synchronization freshness. `clock_timestamp()` would describe actual trigger execution more closely but still would not be an ordering authority; it is not required for P3. Stale/duplicate events must execute **no UPDATE**, so the trigger does not change `updated_at` on redelivery. Trigger-based `updated_at` behavior and concurrent timestamp anomalies need PostgreSQL tests, not ordering assumptions.

## Group Management producer prerequisite (P2.4b)

The Group-side diagram adds `groups.groups.membership_revision: bignatural1 = 1`. Creation of the Group and initial owner membership is **one committed Group transaction**, leaving `membership_revision = 1`. That transaction durably publishes **two independent facts** via the existing Spring Modulith publication registry:

- `GroupCreatedV1(eventId, groupId)` reports organizational Group creation, with **no owner and no membership revision**. Finance ensures the root exists and transitions `provisioning → open`; it does not fabricate a `FinancialActor`.
- `GroupMemberAddedV1(eventId, groupId, userId, membershipRevision=1)` reports the initial owner membership, using the same full-state member-event contract as subsequent additions. It may arrive before or after `GroupCreatedV1`.

Each later successful membership addition/removal atomically advances `groups.groups.membership_revision` **exactly once** under Group-local serialization and publishes a stable, versioned event carrying `eventId`, `groupId`, `userId`, the committed positive `membershipRevision`, and the **resulting** membership state (`deleted`/active). A removal retains the affected user ID even though Group Management may delete the original `group_members` row. Both independent creation facts must have durable publication obligations in the Group creation transaction when the corresponding subscriber exists. A retry preserves the original event identity and version. Role-only changes do not increment the membership revision or grant Finance authority. Insertion of the initial owner is **revision 1**, not a second increment to 2.

The `group_members.id` UUIDv7 identifies one organizational membership incarnation, not its removal or group-wide commit order. Finance never needs that row ID. The Group-side counter is an approved P2.4b schema requirement; P2.0c's existing ER asset does not yet show it.

## Finance provisioning, ordering and concurrency

Each event handler applies one **Finance-local** transaction: ensure `finance.groups(id)` exists (idempotent insert-if-absent), acquire that group's row lock, check lifecycle and relevant source revision, apply its effect, then commit. Concurrent first observations converge on one root via the PK. No Group SQL joins, remote callback, distributed lock or Finance-specific pending-event queue are needed.

- A membership event can create `provisioning` and upsert its actor immediately. Later `GroupCreatedV1` activates the existing root; if creation arrives first, the root may be committed as `open` **with zero actors** until the owner's separate member event arrives. These delivery orders are both valid.
- A member event applies only when `event.membershipRevision > actors.upstream_revision` (or the actor does not exist). Unknown departures insert `deleted=true` tombstones. Older events and exact duplicates are no-ops. Contradictory states at the **same actor and revision** are producer-contract violations and fail observably rather than overwriting data. A later rejoin reuses the same `(group_id,user_id)` key.
- The comparison is **per actor**. Receiving revision 3 for B must not suppress revision 2 for A. Neither a highest-seen Group watermark nor a snapshot baseline is persisted in P3.
- `GroupCreatedV1` opens only a `provisioning` Finance root; duplicate/delayed creation cannot reopen `closed` or `archived` groups or change `current_revision`. Member events never increment that Finance revision either.

The core conditional upsert is equivalent to:

```sql
INSERT INTO finance.actors (group_id, user_id, deleted, upstream_revision)
VALUES (:group_id, :user_id, :deleted, :membership_revision)
ON CONFLICT (group_id, user_id) DO UPDATE
SET deleted = EXCLUDED.deleted,
    upstream_revision = EXCLUDED.upstream_revision
WHERE finance.actors.upstream_revision < EXCLUDED.upstream_revision;
```

Same-revision contradictory events need an additional explicit check: the conditional upsert alone leaves them unchanged and cannot distinguish corruption from legitimate redelivery. Version checking and upsert must be transactional under the root lock. Source publication commits with the Group mutation; Finance consumption commits independently. A publication is completed only after the Finance operation commits. Registry completion failure can cause redelivery, which must be harmless. P2.4a/c own the transport/recovery integration; the actor version guard is the consumer-specific idempotency mechanism replacing a redundant inbox.

### Authorization is not the actor projection

Finance verifies **Group-issued, issuer-/audience-/session-qualified membership attestations** through the approved P2.5 authority flow when authorizing a group-scoped API operation; it must not infer request authority from `finance.actors.deleted` or local row existence. A newly added user with a valid attestation may perform authorized Finance **reads while their FinancialActor has not yet replicated**, provided the resource and operation policy permits the read. Actor-dependent financial operations additionally require the relevant actor(s) to exist and be eligible; an attestation never implicitly creates an actor or bypasses Finance's lifecycle guard. A member removed from Group Management may retain a valid attestation until its bounded expiration; that staleness is handled by the security policy, not by assuming immediate projection delivery. These are **later API/feature enforcement requirements**, not new P3 columns or an already implemented authorization flow.

### Lifecycle versus replication readiness

`provisioning` means that Finance has evidence of a Group but has not yet consumed its `GroupCreatedV1` fact. `open` means that creation was observed, **not** that the owner or other members have necessarily replicated or that the projection is fully caught up. No additional readiness counter is inferred from `current_revision`. Finance refuses monetary mutations in `provisioning`, with a typed not-ready result; the presentation of initialization/absence to an authorized user is left to the relevant UI/API vertical. Reads must never disclose Group existence to an unauthorized caller.

Later `open → closed`, `closed → open`, and terminal `archived` transitions are Finance business operations (P5.3). **Organizational Group deletion is deferred:** no `GroupDeleted` subscriber, `deleted_at` field, timer, hard-delete or terminal-source tombstone is introduced in P3. Deletion cannot safely be enabled without a separate retention/authorization/replay policy that prevents delayed older events resurrecting a retired group. A synchronous Group → Finance deletion preflight is not an accepted substitute for that policy.

## MVP monetary scope, rollout and recovery

P3.3 supplies pure, exact `Money`/`Percentage` and split-strategy calculations, not persisted expense, allocation, settlement or journal tables. The MVP uses one implicit **application-wide fixed two-decimal denomination**, without a currency column or per-group selection. The frontend displays one consistently chosen unit. Its actual code/symbol must be fixed before money-writing features ship; a future currency-management vertical may use a coordinated **Flyway** migration to label prior amounts with that fixed unit **without conversion**, not reinterpret historical values.

The MVP's first Finance listener must be registered before new organizational Group data requiring replication is created. Earlier disposable data is explicitly reset/reseeded or backfilled via a separately verified, writes-suspended Flyway release migration. The publication registry does not retroactively publish facts from before listener registration. Failed ordinary deliveries remain pending/recoverable through the existing Modulith registry and operator-visible retry mechanisms; P3 introduces no separate queue, periodic reconciliation or custom scheduler. Permanent loss of a never-published event is **not** automatically repaired. Optional snapshot-based drift repair and periodic retries remain conditional [Phase 8 hardening](../tasks/backlog.md#phase-8---operational-hardening-and-full-mvp-readiness), not a P3 prerequisite.

## Cross-team handoffs and acceptance

The ownership boundary is the **contract**, not the internal service or repository class used to implement it. Teams may develop against agreed event fixtures and published interfaces without waiting for the other side's production adapter; joint acceptance requires the real producer, registry and consumer.

| Boundary | Provider → consumer | Required handoff |
| --- | --- | --- |
| Group source | **P2.4b → P3.2a/b** | Two independent creation facts; stable event IDs; atomic membership revision assignment and publication obligations; resulting-state add/remove events. The consumer uses only published event contracts, never Group-owned SQL. |
| Durable delivery | **P2.4a + P2.4b → P3.2b**, accepted with **P2.4c** | Source transaction/publication atomicity, independent Finance commit, incomplete-publication recovery and at-least-once redelivery. The consumer's per-actor source version guard supplies idempotency. |
| Finance persistence | **P3.1a/b → P3.2a and P3.4** | One Finance-owned migration and one idempotent root-creation/row-lock contract, shared by membership and financial write paths. No duplicate schema or competing lock primitive. |
| Authorization | **P2.5 → future Finance APIs (P4/P5)** | Verified, session-bound Group attestations authorize scoped access; actor existence/eligibility governs financial participation separately. Neither is a substitute for the other. |
| Arithmetic | **P3.3 → future money-writing features (P4/P5)** | One fixed two-decimal unit, exact arithmetic, deterministic allocation/rounding and conserved totals; no persistence or Group integration in the P3.3 deliverable. |

### Integration scenarios that require coordination

These are **design-time acceptance obligations**, not tests already run. The authorization/API case is a contract for later Finance API verticals, **not** a dependency that blocks the P3 sprint. The [backlog](../tasks/backlog.md#phase-3---finance-actor-synchronization-and-accounting-foundations) assigns their implementation and review gates; task-local specs may add tests without redefining the shared contract.

| Scenario | Required observation / evidence |
| --- | --- |
| **First evidence / reordering** | Concurrent membership events for an unknown Group produce one `provisioning` root and valid actor FKs. `GroupCreatedV1` before or after an initial-owner event at revision `1` (or a later member event) converges; creation alone can produce `open` with no actors. |
| **Replays / ABA** | Leave-before-join creates a tombstone; leave/rejoin and owner-event reorder converge by *actor-local* source version. A stale event is a true no-op (including `updated_at`); contradictory same-actor/same-revision state is an observable error. |
| **Publication failures** | Group creation publishes both facts atomically with the owner row; source rollback publishes neither. Finance commit is independent, registry-completion failure permits harmless redelivery, and incomplete publications remain recoverable. No extra Finance queue is introduced. |
| **Lifecycle / authority lag** | P3 verifies lifecycle guards and no implicit actor creation. **Later Finance API acceptance (P4/P5)** verifies that a valid Group attestation can authorize a read before the caller has an actor and that actor-dependent writes reject missing/ineligible actors. Authorization never relies on the actor flag. |
| **Finance ordering** | Membership events never increment `current_revision`. Later Finance business writes are serialized by the same root lock, allocate distinct committed financial revisions without rollback gaps, and do not unnecessarily serialize different groups. |

The [Phase 3 backlog](../tasks/backlog.md#phase-3---finance-actor-synchronization-and-accounting-foundations) is the authoritative task/dependency plan. P3.0 approval concerns these contracts and the two-table provenance, **not** approval of task-specific implementation designs; P3.1–P3.4 still require their own appropriate specification, test design and human review gates. No P3.2 snapshot/backfill task, currency field, Group-deletion handler or periodic reconciliation scheduler is part of the MVP contract.
