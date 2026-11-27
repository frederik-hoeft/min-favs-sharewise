# Phase 4 design handoff: Expenses, history and balances

**Status:** Proposed shared contract for human P4.0 approval; not an implemented Finance vertical. This document owns the shared schema, domain invariants and integration guarantees. The [backlog](../tasks/backlog.md#phase-4---expenses-history-and-balances-vertical-slice) owns task sequencing, ownership and acceptance gates. Individual implementation tasks retain their own specifications and reviews.

**Audience:** Owners of Finance persistence, expense mutations, financial projections, frontend integration and the subsequent Settlement vertical. Read this with the [Phase 3 foundation](phase-3-foundation.md), [conceptual domain model](../../data-model-design.md), [transaction architecture](../../architecture/transactions.md), [persistence architecture](../../architecture/persistence.md), [security design](../../stack/security.md) and [frontend architecture](../../architecture/frontend.md).

## Scope and provenance

P4 establishes three Finance-owned tables (`expenses`, `allocations`, `change_records`), expense creation/correction/removal, revision-aware expense and balance queries, and the corresponding user-facing workflows. Existing `finance.groups` and `finance.actors` retain their P3 schema and contracts. Settlement operations and lifecycle mutation workflows belong to P5; P4 reserves the journal representation for lifecycle changes without implementing those operations. Percentage splits, attachments, currency management and elaborate history comparison UI are outside the P4 MVP scope. Basic revision selection and expense history remain supported by the backend; a minimal history presentation is sufficient for the demo.

| Marker | Source / authority | Used for |
| --- | --- | --- |
| **DEC** | P4.0 decisions captured in this handoff and the backlog, pending human approval | Unified revisions, payer-only mutations, equal/fixed strategies, COW, participant eligibility, scope reduction |
| **DOM** | [Conceptual domain model](../../data-model-design.md) | FinancialGroup consistency boundary, stored allocations, logical identity, immutable financial history, balance semantics |
| **ER** | [Authoritative yEd ERD](../../assets/er-data-model.pdf) and the P4 diagram reviewed during planning | Table/column names, physical keys, types, nullability and enum choices |
| **P3** | [Phase 3 foundation](phase-3-foundation.md) | Actor identity, root lock, financial revision allocation, monetary unit, authorization separation |
| **REC/PG** | Reconciliation rationale and PostgreSQL mapping in this handoff | Constraint enforcement, indexing, transaction semantics and integration details |

The yEd PDF is the single authoritative visual ERD, providing the technical model and its principal structural constraints. The column tables and invariants in this foundation complement it with mandatory structural constraints (including journal CHECK constraints), domain rules and integration guarantees intentionally omitted from the drawing for readability. Implementations must satisfy both artifacts; omission from the ERD does not make a requirement optional. The earlier [Twin Peaks relational sketch](../../assets/data-model-versioned.svg) provides design provenance, not a competing physical schema.

## Shared identity and revision model

A logical Expense is identified by `(group_id, key)`. Its immutable version is identified by `(group_id, key, revision)`. The key is generated once at creation, then retained for every correction and deletion. A version's `revision` is the FinancialGroup's allocated financial revision, not an independently incremented expense counter. Versions of one logical entity are strictly increasing but need not be consecutive. The same coordinate system applies to future Settlement versions and journal entries, so no per-entity counter or revision translation is required.

Every committed financial mutation allocates exactly one group revision and appends exactly one ChangeRecord. In P4/P5 a mutation affects one Expense, one Settlement or the group's business lifecycle. The Expense version PK expresses logical version identity independently of this operation cardinality; `UNIQUE(group_id, revision)` expresses the current one-version-per-operation policy within the Expense table. The shared journal PK and transactional write protocol enforce aggregate-wide ordering across subjects. Neither membership synchronization nor `provisioning → open` allocates a financial revision.

Optimistic concurrency compares the caller's expected revision with the latest version of the **affected logical entity**, never with `groups.current_revision`. Unrelated group changes therefore do not cause false edit conflicts. Revision tokens are opaque to clients; timestamps and UUID order do not establish financial order.

## P4 schema reference: `finance.expenses`

Each row is one complete immutable version of an Expense, including deletion tombstones. The group and logical key are stable across revisions; the payer is immutable in the MVP. All columns are NOT NULL unless explicitly nullable. Types `bignatural0` and `bignatural1` reuse the P3 signed `int8` nonnegative/positive constraints or equivalent column checks.

| Column | PostgreSQL type and constraints | Provenance / why it exists |
| --- | --- | --- |
| `group_id` | `uuid`; first part of PK; FK → `finance.groups(id)` | **DOM, ER, P3:** Aggregate ownership and shared prefix of group-local FKs. |
| `key` | `uuid`; second part of PK; no default | **DEC, DOM, ER:** Stable logical Expense identity, generated explicitly once and copied unchanged. No separate physical row UUID. |
| `revision` | `bignatural1`; third part of PK | **DEC, P3, ER:** Financial revision assigned by the shared group-local allocator; identifies the exact version. |
| `payer_id` | `uuid`; composite FK `(group_id, payer_id)` → `actors(group_id, user_id)` | **DOM, DEC, ER:** The actor who paid; same-group integrity, distinct from general journal authorship. Caller is the payer in the MVP. |
| `name` | `varchar(128)`; CHECK `length(btrim(name)) > 0` | **ER, DEC:** User-visible required expense label. Application validation rejects blank names. |
| `description` | `varchar(1024)` nullable | **ER, DEC:** Optional user-visible description, versioned with the Expense. |
| `total_amount_minor` | `bignatural1` | **DOM, P3, ER:** Exact positive total in the fixed implicit MVP minor unit. |
| `split_strategy` | `finance.split_strategy` enum (`equal`, `fixed`) | **DEC, DOM, ER:** Preserves the chosen input strategy for display and editing. No percentage feature or percentage column. |
| `deleted` | `boolean DEFAULT false` | **DOM, ER:** Complete COW tombstone; historical versions remain available. |

`PRIMARY KEY (group_id, key, revision)` and `UNIQUE (group_id, revision)` supply the initial indexes. The PK supports group-scoped logical history and latest-version lookups; additional indexes require a demonstrated query need. Expense timestamps belong to the corresponding ChangeRecord, not a second independent timestamp source.

## P4 schema reference: `finance.allocations`

One materialized participant share per Expense version. Allocations are immutable children of that version; the participant set reconstructs the equal-split input, and stored amounts reconstruct fixed-split input. The stored shares are authoritative for historical accounting.

| Column | PostgreSQL type and constraints | Provenance / why it exists |
| --- | --- | --- |
| `group_id` | `uuid`; first part of PK and both composite FKs | **P3, ER:** Common group scope enforces that the expense and participant belong to the same Finance aggregate. |
| `expense_key` | `uuid`; second part of PK | **DEC, DOM, ER:** Logical Expense identity; explicit rather than relying on uniqueness of the revision alone. |
| `expense_revision` | `bignatural1`; third part of PK | **DEC, DOM, ER:** References the exact immutable parent version. |
| `participant_id` | `uuid`; fourth part of PK | **DOM, ER:** FinancialActor receiving the share, retained even after departure. |
| `amount_minor` | `bignatural0` | **DOM, P3, ER:** Materialized nonnegative share; zero is valid, including when equal-split rounding produces zero. |

`PRIMARY KEY (group_id, expense_key, expense_revision, participant_id)` prevents duplicate participants. The composite FK `(group_id, expense_key, expense_revision)` references `expenses(group_id, key, revision)`; `(group_id, participant_id)` references `actors(group_id, user_id)`. No surrogate Allocation ID, percentage input, cross-context FK or independent lifecycle is needed. Do not physically cascade deletion of financial history.

Every Expense version has at least one allocation and its shares sum exactly to the positive expense total, including complete deletion versions. A payer may also be a participant. These aggregate invariants are enforced by the shared transactional write operation and P3.3's exact arithmetic, not by an ordinary row-local PostgreSQL CHECK over other rows. Equal shares and remainder distribution follow the deterministic P3.3 contract; fixed shares are provided explicitly, nonnegative and sum to the total. Do not use floating-point calculations or recalculate stored historical allocations.

## P4 schema reference: `finance.change_records`

One immutable entry per committed financial revision, shared by Expense, future Settlement and financial lifecycle operations. Explicit subject and operation discriminators encode intent independently; nullable payload columns are constrained by the declared subject. This intentional row-local redundancy enables direct type dispatch and named structural checks without depending on another record for discriminator consistency.

| Column | PostgreSQL type and constraints | Provenance / why it exists |
| --- | --- | --- |
| `group_id` | `uuid`; first part of PK; FK → `groups(id)` | **DOM, P3, ER:** Group-local journal ownership. |
| `revision` | `bignatural1`; second part of PK | **DEC, DOM, ER:** Unique position in the financial history, shared with the affected version. |
| `subject` | `finance.change_subject` enum (`expense`, `group`) | **DEC, ER:** Declared subject, selects required payload and legal operation kinds. P5 extends this enum for settlements. |
| `kind` | `finance.change_kind` enum (`create`, `update`, `delete`) | **DEC, ER:** Operation, independent of subject; group state transitions are `update`. |
| `actor_id` | `uuid`; composite FK `(group_id, actor_id)` → `actors(group_id, user_id)` | **DOM, ER:** Modifying actor; retained historical authorship. Equals payer for MVP expense operations, but not necessarily for other subjects. |
| `created_at` | `timestamptz DEFAULT now()` | **DOM, ER, PG:** Diagnostic operation timestamp, not the ordering authority (transaction-start semantics as in P3). |
| `expense_key` | `uuid` nullable | **DEC, DOM, ER:** Logical subject key; composite FK `(group_id, expense_key, revision)` → `expenses(group_id, key, revision)`. |
| `new_state` | `finance.group_state` nullable | **DEC, DOM, ER:** Resulting financial lifecycle state for a group update. Previous state is derived from the earlier transition or the initial `open` baseline. |

`PRIMARY KEY (group_id, revision)` prevents duplicate journal positions. The nullable Expense FK uses PostgreSQL `MATCH SIMPLE`, allowing `expense_key` to be NULL for group updates; the subject constraints make the reference mandatory for Expense changes. `MATCH FULL` would incorrectly reject the partially NULL group-update reference.

The following are mandatory row-local invariants (constraint names and SQL formulation are adapter/migration details):

| Subject | Expense key | New state | Allowed kinds |
| --- | --- | --- | --- |
| `expense` | Required | NULL | `create`, `update`, `delete` |
| `group` | NULL | Required, not `provisioning` | `update` |

Enforce payload validity and subject/kind compatibility as separate named CHECK constraints. Both discriminators are NOT NULL; the checks must reject unexpected enum/payload combinations, not accidentally accept SQL UNKNOWN for an invalid required field. P5 extends the payload with a nullable `settlement_key` and its group/key/revision FK, subject enum and exclusive-payload checks, in a later coordinated migration. P4 does not introduce a Settlement table, field or FK.

An Expense journal entry references exactly the version created by its operation. Conversely, every committed Expense version must have exactly one journal entry, with the corresponding operation kind and deletion flag (`delete` iff `deleted=true`). A `create` establishes a previously absent logical key; `update` and `delete` require an existing live version. The journal FK alone does not enforce the reverse existence or these cross-row invariants. The shared atomic write protocol enforces them; do not introduce circular FKs solely for reverse existence in the MVP.

For an activated group, financial revision zero represents the initial `open` business state. Historical lifecycle at revision R is the latest group update at or before R, or `open` if none exists. `groups.state` is the materialized current state; future P5 lifecycle operations validate the transition against that locked row, update it, allocate the revision and append the new state atomically. No `previous_state` column is needed. Technical provisioning is excluded from this historical timeline; a provisioning root is not represented as an activated group at revision zero.

## Mutation, authorization and failure contracts

The Finance application operation authorizes access through the [verified, Group-issued, session-bound attestation policy](../../architecture/distributed-authorization.md). The actor projection is not the authorization authority; an authorized reader need not have a replicated FinancialActor. A monetary mutation additionally requires an existing, active caller actor and an `open` FinancialGroup. Never provision actors or roots from a user request or arbitrary UUID. No Group-owned SQL or synchronous Group lookup inside the Finance transaction. Caller identity comes from the verified primary credential, never from a caller-selected payer or journal actor ID. A stale attestation remains subject to the existing bounded-validity policy; local actor eligibility is a distinct check.

| Operation | Shared business contract |
| --- | --- |
| Create | Caller is payer. Generate a new logical key; all participants must be existing active actors in the group. |
| Correct | Caller is original payer; payer/key unchanged. Require expected revision of latest live version and a complete valid replacement. Previously referenced former participants may remain or have their shares corrected. **Proposed for P4.0 approval:** newly introduced participants must be active, including a former participant removed from the immediately preceding version and later reintroduced. |
| Remove | Caller is original payer; require expected revision of latest live version. Append a complete deleted version with its preceding allocations, without requiring historic participants to become active. |
| Read | Valid scoped authorization, not actor existence/activity. Authorized Group members may inspect the complete retained financial history, including revisions predating their membership, subject to the existing attestation validity policy. Retained former-member actor identity alone grants no access. Preserve historical payer/participant references and balances even for departed actors. |

The participant checks belong to the application/domain operation, not just the HTTP adapter. Membership eligibility is the accepted Finance projection under the shared root lock; the system does not claim zero-lag authoritative participant state. A newly added member can read before their actor replicates, but a mutation needing that actor must fail predictably until replication. A departed payer cannot mutate while inactive; rejoin restores eligibility subject to current authorization. There is no payer-transfer or administrative override in the MVP.

All expense mutations use the same group-local operation boundary:

1. Verify scoped authorization and syntactic input; begin one physical `Transactions` segment for the mutation.
2. Lock the existing Finance group using P3.1b/P3.4's shared protocol; check lifecycle, actor eligibility, latest logical version and expected revision under that lock.
3. Validate complete replacement state and shares. Reuse P3.3 pure allocation logic when split inputs change. Display-only edits copy persisted shares exactly, without recalculation or revalidating retained former actors as newly added participants.
4. Allocate the next group financial revision; insert the immutable Expense and all its allocations, then append its ChangeRecord. The shared persistence capability participates in the caller's segment and never starts a nested or independently committed segment.
5. Commit all effects together and return the committed logical key/version. Rejection, conflict, overflow or persistence failure leaves no new committed version, allocations, journal entry or counter increment.

Every successful edit, including name/description changes, is a financial-domain revision even if balances do not change. Full COW preserves historical labels and allocations; no separate metadata history or structurally shared allocation set is introduced. A deletion is terminal for that logical Expense in the MVP; a new expense requires a new key. No in-place updates of historical Expense/Allocation/ChangeRecord rows.

Distinguish typed unauthorized/forbidden, absent/not-ready, invalid input, stale-version conflict and infrastructure failure through the existing application result and HTTP ProblemDetail conventions. Unauthorized callers must not learn group or expense existence. A missing actor is an eligibility/not-ready outcome, not grounds for refreshing IAM credentials or implicitly creating a row. The exact error codes and HTTP paths are frozen in task-level API specifications before frontend integration; do not expose successful results before physical commit. An ambiguous transport failure after dispatch is not permission to automatically retry creation (a separate idempotency-key feature is not assumed).

## Read and accounting contracts

Effective-state projections share one selection rule: for each logical key in the group, select the greatest version revision at or before the requested committed group revision, then exclude its tombstone. Filtering `deleted=false` before latest-version selection incorrectly resurrects removed records. Latest Expense detail uses this rule at the current group revision.

Journal entries identify the exact immutable subject version, including deletion versions; inspecting such a version, if exposed, must not substitute an earlier live version or confuse a deletion tombstone with effective state. The complete history remains persisted. P4.3a and the frontend owners decide during task specification which history/detail operations to expose (for example a per-expense history collection or revision-qualified inspection), their lookup semantics and presentation. A separate exact-version operation or endpoint is not an MVP requirement. Version references use the existing logical key and group revision, not independent entity version numbers; journal timestamps can supply human-readable history labels. No current-row flag, balance cache or second history engine is required.

The [P3 lifecycle/readiness contract](phase-3-foundation.md#lifecycle-versus-replication-readiness) applies before resolving any financial snapshot: after scoped authorization, a missing or provisioning root yields a not-ready result, regardless of revision. An activated revision-zero root is a valid empty financial snapshot. An omitted revision selects the current committed group revision. An explicit revision must be between zero and the group's current revision inclusive; invalid/future revisions are rejected predictably. A response includes the resolved group revision. Read the revision and all contributing data from a single consistent database snapshot (one SQL statement or suitable transaction isolation); independent read-committed statements are not a snapshot. Use targeted database-side projections via Finance-owned ports, not aggregate graph loading. Current and historical queries can be developed against invariant-preserving fixtures before the mutation service is available.

The [domain balance formula](../../data-model-design.md#current-balances) reduces in P4 to paid expense totals minus assigned stored shares. Aggregate contributions without multiplying expense totals by their allocation count. Preserve every actor with a nonzero historical contribution regardless of its current `deleted` flag. Membership itself is not versioned by the financial revision; do not claim to reconstruct past organizational rosters or their zero-balance actors. Active actors with zero current balances can be included for presentation; keep this distinction explicit in historical results. Group balances sum to zero. SQL sums and Java/API representations must handle aggregate values exactly, including totals beyond a single signed 64-bit expense; reject unrepresentable results explicitly rather than wrap or truncate. P5 adds signed Settlement contributions (`sent - received`) using the same revision and snapshot without matching them to expenses or recalculating allocations.

## API and frontend coordination

The following are semantic handoffs, not a second independent API specification. Before parallel frontend/backend implementation, the responsible lanes agree the concrete routes, DTO shapes, JSON exact-integer representation, error codes and mock fixtures. All monetary minor units and revision values must round-trip exactly across Java/PostgreSQL and TypeScript/JSON (for example canonical decimal strings for 64-bit values, not unguarded JavaScript numbers). Use the same fixed unit/display convention agreed in P3.3.

| Capability | Shared request/result content |
| --- | --- |
| Expense list/detail | Group scope; current effective list/detail, with revision-qualified effective views as required by the history workflow. Return resolved group revision, logical key, version token, payer, name/description, total, strategy; detail includes participant allocations. |
| Participant choices | Group-scoped actor IDs and local eligibility for new selections; existing expense detail retains previously referenced actors even if departed. P4.3a coordinates the minimal read projection needed by the expense forms. |
| Create | Group scope, name/description, total, strategy, participants and fixed shares if applicable; no caller-selected payer; committed key and revision returned. |
| Correct/remove | Group scope, logical key, expected entity revision; correction supplies complete replacement inputs. Return committed result or typed conflict; reload rather than silently overwrite. |
| Balances | Group scope and optional historical revision; resolved revision and actor-to-signed-balance projection using stored amounts. |
| Journal/history | Group scope; ordered entries with subject, kind, modifying actor, timestamp and exact subject version reference. Task specifications agree the minimal history inspection capabilities and distinguish exact-version references from effective-state selection at a group revision. |

Use the [existing authenticated workspace/API boundary](../../architecture/frontend.md#browser-api-boundary) and [shared attestation acquisition and recovery](../../architecture/distributed-authorization.md#frontend-recovery-and-session-lifetime). Feature components do not manage JWTs, session identities or refresh. Coordinate the static-export-compatible Group workspace route and member display data with P2.2/P2.5; Finance does not read IAM/Group tables or make its own authentication coordinator. Current organizational membership lists alone are insufficient for rendering historical actors; retain stable user IDs and use a safe fallback label when current display metadata is unavailable. No cross-context data join is required. The UI submits inputs and displays persisted amounts; server validation and allocation remain authoritative. Handle loading, no expenses, not-ready/actor lag, permission denial, conflicts and transport errors explicitly. A simple expense list/form, balance overview and revision-aware history suffice; no separate comparison dashboard or attachments.

## Cross-team handoffs and acceptance

The backlog defines the implementation order. The owners agree immutable operation DTOs and adapter contracts early; fixtures unblock independent design and tests, not real integration acceptance.

| Boundary | Provider → consumer | Shared deliverable |
| --- | --- | --- |
| P3 root/actors/revision | P3.1/2/4 → P4.1–4.3 | Shared root lock and allocator in the same transaction; projected actors, no second lock or competing sequence. |
| Accounting | P3.3 → P4.2, P5 | Exact fixed-unit Money and equal/fixed split validation/rounding, no percentage dependency. |
| Persistence | P4.1a → P4.1b/P4.2/P4.3 | One migration owner, exact composite identities, generated metadata as required, journal constraints; no competing DDL. |
| Financial writes | P4.1b → P4.2a/b, P5 | Shared in-segment atomic persistence/journal capability; mutations own business validation and transaction outcome, not a duplicate write protocol. |
| API authorization | P2.5 → P4.2/P4.3/P4.4 | Real verified attestations and shared frontend transport; test against fixtures first, integrate before acceptance. |
| Query/API | P4.3 → P4.4 and P5.2 | Consistent revision-qualified projections; P5 extends the single balance implementation. |
| Frontend | P4.2/P4.3 + P2 Group workspace → P4.4 | Approved API fixtures, shared routes and components; live integration and manual browser acceptance before closure. |

P5 design can start once P4.0's revision/journal contracts are agreed; Settlement persistence extends the journal after P4.1a's migration (coordinate migration ordering), and its operations depend on the common write/revision boundary, not the completed expense UI. P5.2 extends the shared balance query contract; do not build a second competing accounting projection. Settlement authority and lifecycle authorization remain P5 decisions. Any proposed deferral of lifecycle operations or history UI beyond the existing MVP commitment needs explicit human scope approval.

### Shared integration scenarios

These are acceptance obligations, not executed tests. Unit/property tests establish pure arithmetic and operation validation; PostgreSQL Integration/E2E tests establish actual constraints, committed atomicity, consistent snapshots and locking. HTTP and browser tests verify authorization and live frontend handoffs.

| Scenario | Required evidence |
| --- | --- |
| Schema/subject constraints | Reject wrong-group actor/expense FKs, duplicate participants, invalid revision/amount/name, invalid discriminator/payload/kind; allow valid group journal payload with nullable Expense FK. |
| COW and conservation | Equal/fixed creation, zero shares, complete corrections/deletion, description-only copy, positive totals and exact conserved shares; old rows unchanged. No percentage requests accepted. |
| Authorization/replication | Cross-group or mismatched credentials fail closed; non-payer mutation denied; attested reader without actor permitted on an activated root, including pre-join history; former actor without valid scoped authorization denied; mutation without active payer rejected; new expenses reject former participants; corrections retain former participants per approved policy. |
| Conflicts and concurrency | Same-entity racing edits with identical expected revision yield one winner; unrelated expenses do not spuriously conflict; projection and financial mutation share root lock; different groups do not share a global lock. |
| Atomic failure | Inject failure after revision allocation and after partial persistence/journal work; committed counter, versions, allocations and history remain unchanged. Verify commit completion, not just rollback-only fixtures. |
| Readiness | Authorized business operations reject missing/provisioning roots; an activated revision-zero root returns a valid empty financial snapshot. Unauthorized callers receive no existence/readiness information. |
| Historical queries | Reconstruct creation/correction/deletion at selected revisions, including zero and gaps between entity versions; no resurrection from early tombstone filtering; stored shares used; reads remain snapshot-consistent during concurrent writes. Any exposed version inspection distinguishes exact journal references (including deletion versions) from effective-state lookup; task specifications define the necessary inspection operations and corresponding tests. |
| Balance correctness | Include retired actors' contributions, no double counting, exact sum zero and overflow behavior; P5 verifies Settlement contributions at the same revision. |
| Browser/API integration | Authorized user creates, edits and removes own expense, sees current balances and basic history, handles conflict and replication lag through the shared client. Live Group attestation and deployed static frontend smoke required; no simulated provider substitutes for final acceptance. |

The [P4 backlog](../tasks/backlog.md#phase-4---expenses-history-and-balances-vertical-slice) tracks review and ownership. P4.0 approval does not approve individual implementation specifications or establish that these features already exist.
