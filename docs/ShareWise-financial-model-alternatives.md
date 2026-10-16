# ShareWise: Alternative Financial Models

**Status:** Discussion draft, not an agreed design  
**Scope:** User-facing financial behavior, invariants, and architectural trade-offs

## Shared assumptions and evaluation criteria

- All real payments occur **outside ShareWise**. The application records claims and reported transfers between friends; it neither executes nor verifies payments.
- An **expense** records who paid a cost and how that cost is allocated among participants. A **transfer** records money reportedly sent from one participant to another. Both affect a group's net balances.
- Membership and authorization remain necessary, regardless of the financial model. Historical financial records must not disappear when a member leaves.
- The models below represent **alternatives**, not successive implementation stages. Models 3 and 4 can support nearly identical user experiences.
- **Impact ratings** reflect our current priorities for a friend-oriented bookkeeping tool, not objective scores: **High** = major product or architecture consideration; **Medium** = meaningful trade-off; **Low** = secondary or readily mitigated. Benefits and costs should be weighed separately, not summed numerically.

## 1. Strict group lifecycle

A group follows a strictly forward-only financial lifecycle: **Open -> Closed -> Archived**.

- **Open:** Members create, edit, and remove expenses.
- **Open -> Closed:** Members approve/finalize the group's expenses. Net balances are frozen and handed over for settlement.
- **Closed:** No new or modified expenses; members record repayments against the finalized balances.
- **Closed -> Archived:** Once settlement-completion conditions are met, the group becomes read-only.

**Boundary:** A single immutable **group-level closing snapshot** moves from Expense Management to Settlement Management. Settlement consistency applies to that finalized group snapshot.

### Pros and cons

| Type | Impact | Consideration |
| --- | --- | --- |
| Pro | **High** | Clear financial phases and a stable snapshot make outstanding obligations easy to explain and settle. |
| Pro | **High** | Clean asynchronous handoff: Settlement Management need not replicate the mutable expense ledger. |
| Pro | Medium | Strong group-level financial invariants can be enforced locally at closure. |
| Con | **High** | No late expenses or corrections once settlement begins; inconvenient for informal groups of friends. |
| Con | **High** | Voting, closure races, corrections, and disputed/unfinished settlements introduce extra workflows and coordination. |
| Con | Medium | Strict guarantees may be disproportionate when actual payments cannot be verified by ShareWise. |

## 2. Strict expense lifecycle

Each expense has its own forward-only lifecycle: **Draft -> Submitted**, independently of the group and other expenses.

- **Draft:** The expense owner may edit or delete it.
- **Submitted:** The expense and its distribution become immutable and contribute to settlement calculations.
- **Corrections:** Submitted records require a linked reversal or adjustment, rather than editing the original submission.
- **Ongoing groups:** New expenses can be drafted and submitted while previously submitted expenses are being settled.

**Boundary:** Each submitted expense produces an immutable **per-expense snapshot** for Settlement Management. This preserves an expense's history without requiring an entire group to close before settlements can begin.

### Pros and cons

| Type | Impact | Consideration |
| --- | --- | --- |
| Pro | **High** | New expenses may continue alongside ongoing settlements; less restrictive than group-level freezing. |
| Pro | Medium | An immutable submitted expense is a natural event/replication boundary and provides a clear historical record. |
| Pro | Medium | Submission is a useful distinction if the business wants explicit responsibility for finalized expense claims. |
| Con | **High** | Users cannot simply fix submitted mistakes; reversal/adjustment records and corresponding UI are required. |
| Con | Medium | Expense submission does not establish that other participants agreed to the debt. |
| Con | Medium | Group-level netting and arbitrary transfers can still change overall balances, so immutability of each expense does not imply immutable group balances. |
| Con | Medium | Per-expense settlement tracking creates additional complexity if users primarily care about their overall net balance. |

## 3. Dynamic financial model, separate services

There are no explicit *financial* submission or finalization phases. Expenses and reported transfers are independently editable bookkeeping records.

- Expenses may be recorded, corrected, or deleted while the group's expense-entry policy permits it.
- Transfers may be recorded independently of particular expenses or existing debts; an overpayment can reverse who owes whom.
- Current balances are calculated from the combined contributions of expenses and transfers.
- Expense Management and Transfer Management own distinct records. A separate Balance component/service reads or projects both streams, accepting temporarily inconsistent views.

**Boundary:** No financial snapshot handoff. Expense and transfer records have **separate persistence and consistency boundaries**. Invariant enforcement is local to each record/service, without a distributed requirement that transfers match existing debts.

### Pros and cons

| Type | Impact | Consideration |
| --- | --- | --- |
| Pro | **High** | Very flexible for friends: late corrections and free-form transfers do not require settlement workflows. |
| Pro | **High** | Expenses and transfers can evolve, deploy, and scale independently. |
| Pro | Medium | No coordinated financial transaction or cross-service settlement invariant is required. |
| Con | **High** | Balance queries can combine different committed moments; a displayed group balance may be temporarily inconsistent. |
| Con | **High** | Reconstructing an exact historical *group-wide* balance requires coordinating independent revision histories and their ordering. |
| Con | Medium | An extra balance service/projection and associated freshness, failure, and recovery behavior increase infrastructure complexity. |
| Con | Medium | Read-time aggregation across services can couple balance availability to both providers unless replicated projections are used. |

## 4. Dynamic financial model, unified context

Retain Model 3's flexible user behavior, but place expenses, transfers, and balance calculation in **one Financial Management bounded context and transactional persistence boundary**.

- Expenses and transfers remain **distinct domain entities and normalized tables**: expenses have payers and cost allocations, while transfers have sender and recipient information.
- Both contribute financial effects to the group's dynamically calculated net balances. Transfers need not settle specific expenses.
- Expense and transfer changes preserve immutable revisions or equivalent audit history.
- A balance query can use one consistent database snapshot, and the group's financial lifecycle can be enforced locally.

**Boundary:** No financial cross-service consistency problem. A bounded context may contain **multiple aggregates**; there is no requirement to load an entire group and its history as one aggregate/object.

### Pros and cons

| Type | Impact | Consideration |
| --- | --- | --- |
| Pro | **High** | Preserves flexible bookkeeping behavior while allowing consistent, explainable expense-plus-transfer balance queries. |
| Pro | **High** | Group closure/archival and financial edits can share one transactional consistency boundary, avoiding distributed write-blocking coordination. |
| Pro | **High** | Historical balance reconstruction is substantially simpler with one locally ordered revision history. |
| Pro | Medium | Eliminates financial data replication, distributed read aggregation, and an independent balance-service failure mode. |
| Con | **High** | Expense and transfer workloads cannot be scaled or deployed independently without later extraction. |
| Con | Medium | The financial context owns a broader model and requires disciplined internal modules to preserve cohesion. |
| Con | Medium | Serializing changes for a strict per-group history introduces write contention within heavily active groups. |
| Con | Low | A single financial database may grow larger, although indexing, partitioning, retention, and horizontal application replicas remain available. |

## Side-by-side summary

| Dimension | 1. Group lifecycle | 2. Expense lifecycle | 3. Dynamic / split | 4. Dynamic / unified |
| --- | --- | --- | --- | --- |
| Financial finalization boundary | Whole group | Individual expense | None | None |
| Late expenses during settlement | No | Yes | Yes, while group open | Yes, while group open |
| Expense correction | Before group closure | Before submission; later via adjustments | Direct, with revisions | Direct, with revisions |
| Transfer linked to debt | Normally group-level obligation | Depends on settlement policy | No | No |
| Balance consistency | Frozen closing snapshot | Submitted expense records | Eventually consistent across services | Consistent local database snapshot |
| Financial service structure | Expenses -> settlements | Expenses -> settlements | Expenses + transfers + balance projection | Unified financial context with separate entities |
| Relative user flexibility | Low | Medium | High | High |
| Primary complexity | Closure agreement and correction | Submission/reversal process | Distributed reads/history | Larger cohesive domain and local history |

## Cross-cutting questions for the selected model

The following decisions are **independent of Models 3 and 4** and should be made explicitly:

1. **Closure:** Proposed meaning: stop creating/editing/deleting expenses, but continue recording transfers. This is not necessarily a financial snapshot or a claim that all balances are final and may be reopened by the group at a later time.
2. **Archival:** Proposed meaning: all financial records become read-only, including transfers. A zero-balance prerequisite and unanimous vote remain optional, unresolved business policies. Permanently archived records cannot be modified.
3. **Versioning:** Preserve changes to expense details **and allocations**, plus transfer changes/deletions. A per-entity version proves its own history; a per-group revision can support exact historical group-wide balance views.
4. **Participants vs. membership:** Keep historical financial participants even after current membership is removed. Current membership governs authorization, not the lifetime of financial records.
5. **Authorization freshness:** A replicated membership projection can be stale after revocation. Define whether bounded delays are acceptable or if sensitive operations need a current check.
6. **Balance semantics:** Net balances are derived financial positions, not unique bilateral settlement obligations. Suggested transfers are recommendations, not the authoritative debt model.
7. **Payment evidence:** ShareWise records *reported* external transfers. Define whether one party may enter/edit transfers unilaterally or both parties must confirm them.

## Technical follow-ups (not part of the model selection)

### A. Append-only group financial change index

A sparse, append-only `GroupFinancialChange` table could reference immutable `ExpenseRevision`, `TransferRevision`, or lifecycle-transition records. Its rows form a journal of changes; current values still live in separate normalized tables or current-revision pointers.

A database-generated primary key alone **does not guarantee commit order** across concurrent transactions. For reliable *per-group* ordering, serialize financial writes by locking the group's financial-state row and transactionally incrementing `groupRevision`; assign that revision to the change row and the immutable changed record. Enforce `UNIQUE(groupId, groupRevision)`. Rollback must roll back both data and revision publication. An autoincrement key remains useful as a surrogate identifier, but not as a time oracle.

Historic balances also require preserving transfer history, expense allocation history, deletions (tombstones), and lifecycle changes. A change index alone does not reconstruct state if referenced records are overwritten or removed. The journal represents **the order in which ShareWise accepted changes**, not when external transfers actually occurred. Ensure every change row refers to exactly one appropriately typed revision and that its referenced record belongs to the same group.

### B. Long-term archival and deletion

Marking a group archived and retaining its data in ordinary tables is the **simplest default**. Large tables are not inherently slow for indexed per-group queries; access patterns, indexing, working-set size, and maintenance matter more than raw row count.

If cold archival is later necessary for cost/retention reasons, a possible extraction protocol would be:

1. **Finance** atomically enters `ARCHIVING`, rejects further financial writes, and publishes a durable archival request.
2. The archive consumer **idempotently** exports a stable, complete snapshot (including revisions and financial participants), stores it under a unique snapshot ID, and checks its row counts/checksums against a source-provided manifest. A frozen source allows streaming/paginated export and retries without requiring one long-lived HTTP/database transaction.
3. Finance receives a durable acknowledgment, verifies it, records where archived data is served, and transitions to `ARCHIVED`.
4. Group Management follows the authoritative archival event to update its routing/display projection.
5. Only **after** the archive is verifiably readable should a separate retryable retention job purge Finance's cold detail rows; keep a durable group tombstone/routing pointer.

## Random Other Idea I just had

As a nice-to-have addition to stand out among other groups:

- add an (independent) file storage service and allow users to upload e.g., pictures of receipts, invoices, etc., which can then be linked to specific expenses.
    - AuthZ could work by providing a group ID to the file service, so that only group members (with that group ID in their JWT claims) can access the uploaded files.