# P4.1a - Expense, allocation and journal schema: specification

**Status:** Draft for review. Precondition: the [Phase 4 foundation](../../integration/phase-4-foundation.md) and the updated [authoritative ERD](../../../assets/er-data-model.pdf) are approved (P4.0). If P4.0 changes the shared schema, this specification follows that decision before implementation. **Scope:** [P4.1a](../backlog.md#phase-4---expenses-history-and-balances-vertical-slice). **Workflow:** [AI development workflow](../../../development/ai-development-workflow.md); an implementation plan follows after this specification is approved.

## Architectural solution and scope

Finance owns the immutable financial history of a group. P4.1a adds the physical schema for it: one additive Finance migration that creates `finance.expenses`, `finance.allocations` and `finance.change_records`, three Finance-owned enum types, and the regenerated jOOQ metadata. The [Phase 4 foundation](../../integration/phase-4-foundation.md#p4-schema-reference-financeexpenses) is authoritative for the shared physical model and its provenance; this specification turns it into verifiable requirements. The existing P3 objects (`finance.groups`, `finance.actors`, `group_state`, `bignatural0`, `bignatural1`) are reused unchanged.

The schema enforces every **row-local** and **referential** invariant: composite keys, same-group foreign keys, positive and nonnegative domains, label checks, and the journal's subject/payload/kind checks. **Cross-row** invariants are not enforced in SQL. They belong to the shared write operation in P4.1b, which runs under P3's root lock in one transaction:
- every Expense version has at least one allocation, and its shares sum to the total;
- every Expense version has exactly one journal entry, with `delete` if and only if `deleted = true`;
- `create` introduces a new key, while `update` and `delete` follow an existing live version;
- revisions come from the group-local allocator.

The foundation explicitly rejects circular FKs, triggers and deferred cross-row checks for these invariants in the MVP.

**Out of scope:**
- the persistence port and jOOQ adapter for writes (P4.1b);
- the revision allocator (P3.4);
- expense operations, validation and the HTTP API (P4.2);
- queries and balances (P4.3);
- the frontend (P4.4);
- Settlement tables, columns or the `settlement_key` journal payload (P5, which extends the journal later in its own migration);
- currency columns, percentage splits, attachments and caches;
- additional indexes without a demonstrated query need;
- data backfills, since there is no existing financial history.

## Schema requirements

All columns are `NOT NULL` unless stated otherwise. No column has a generated default for identifiers. No FK cascades (`ON DELETE`/`ON UPDATE` stay `NO ACTION`). Named constraints follow the repository convention `<schema>_<table>_<purpose>` and are binding, so tests and P4.1b error mapping can refer to them; primary and foreign keys keep PostgreSQL's default names.

| ID | Requirement |
| --- | --- |
| EXP-S01 | Enum types `finance.split_strategy` (`equal`, `fixed`), `finance.change_subject` (`expense`, `group`) and `finance.change_kind` (`create`, `update`, `delete`), exactly these values in this order. `USAGE` is revoked from `PUBLIC` and granted to `sharewise_app`, as in P3.1. |
| EXP-S02 | `finance.expenses`: `group_id uuid`, `key uuid`, `revision finance.bignatural1`, `payer_id uuid`, `name varchar(128)`, `description varchar(1024)` **nullable**, `total_amount_minor finance.bignatural1`, `split_strategy finance.split_strategy`, `deleted boolean DEFAULT false`. `PRIMARY KEY (group_id, key, revision)`; `UNIQUE (group_id, revision)` named `finance_expenses_group_revision_unique`; FK `group_id → finance.groups(id)`; FK `(group_id, payer_id) → finance.actors(group_id, user_id)`; `CHECK (length(btrim(name)) > 0)` named `finance_expenses_name_not_blank`. |
| EXP-S03 | `finance.allocations`: `group_id uuid`, `expense_key uuid`, `expense_revision finance.bignatural1`, `participant_id uuid`, `amount_minor finance.bignatural0`. `PRIMARY KEY (group_id, expense_key, expense_revision, participant_id)`; FK `(group_id, expense_key, expense_revision) → finance.expenses(group_id, key, revision)`; FK `(group_id, participant_id) → finance.actors(group_id, user_id)`. No surrogate ID. |
| EXP-S04 | `finance.change_records`: `group_id uuid`, `revision finance.bignatural1`, `subject finance.change_subject`, `kind finance.change_kind`, `actor_id uuid`, `created_at timestamptz DEFAULT now()`, `expense_key uuid` **nullable**, `new_state finance.group_state` **nullable**. `PRIMARY KEY (group_id, revision)`; FK `group_id → finance.groups(id)`; FK `(group_id, actor_id) → finance.actors(group_id, user_id)`; FK `(group_id, expense_key, revision) → finance.expenses(group_id, key, revision)` with `MATCH SIMPLE`, so a group entry with `expense_key IS NULL` is not checked against expenses. |
| EXP-S05 | Two separate named CHECK constraints on `change_records`, formulated as a disjunction of positive branches so every invalid combination evaluates to FALSE (never NULL) and unknown future subjects fail closed. `finance_change_records_subject_payload_check`: `(subject = 'expense' AND expense_key IS NOT NULL AND new_state IS NULL) OR (subject = 'group' AND expense_key IS NULL AND new_state IN ('open', 'closed', 'archived'))`. `finance_change_records_subject_kind_check`: `subject = 'expense' OR (subject = 'group' AND kind = 'update')`. No `CASE` without `ELSE false` and no negated subject test (`subject <> 'group' OR …`), which would silently accept P5's later subject value. |
| EXP-S06 | One additive Flyway migration `db/migration/finance/V<timestamp>__finance_expenses_allocations_journal.sql`, versioned after every migration on `main` at merge time, applied by the migration role. Existing objects, data and historical migrations are unchanged. The runtime role `sharewise_app` cannot create or alter schema objects. |
| EXP-S07 | **Append-only history:** after `CREATE TABLE`, the migration runs `REVOKE UPDATE, DELETE ON finance.expenses, finance.allocations, finance.change_records FROM sharewise_app`; only `SELECT, INSERT` remain (TRUNCATE, REFERENCES and TRIGGER are never granted by the platform defaults). This enforces the foundation rule "no in-place updates of historical Expense/Allocation/ChangeRecord rows" in the database, following the precedent of `groups.invitations`. Consequence: the runtime role cannot row-lock history rows (`FOR UPDATE/SHARE` needs `UPDATE`); latest-version checks rely on P3's root lock, as the foundation requires. FK checks are unaffected. P3 tables keep their privileges; P5 applies the same revoke to new Settlement tables and to any history table it recreates. |
| EXP-S08 | Regenerate Finance jOOQ metadata from the migrated database with the existing opt-in `jooq-codegen` profile (`finance` execution), and commit all regenerated files of that execution (new tables, records and enums, plus updated `Keys`, `Tables`, `Finance` and `Domains`) under `finance/internal/persistence/generated`. Ordinary builds neither generate nor need a database. No handwritten substitute. Module verification still passes; no new module dependency. |

**What the schema deliberately allows** (because these are cross-row rules owned by P4.1b):
- an expense version without allocations or journal entry;
- shares that do not sum to the total;
- a journal `kind` that contradicts `deleted`;
- gaps between revisions.

Tests document these as accepted at the SQL level, so nobody mistakes them for missing constraints.

## Failure behavior

Every violation fails the whole statement with a PostgreSQL error: integrity violations `23503` (FK), `23505` (unique), `23514` (table or domain CHECK), `23502` (NOT NULL); and `22001` (value too long), `42501` (missing privilege), `22P02` (invalid enum literal in SQL; a JDBC `setString` bind yields `42804` instead). Nothing is partially written. Mapping these errors to typed application results belongs to P4.1b/P4.2. A migration failure leaves the database at the previous Flyway version.

## Verification and acceptance

PostgreSQL 18 is the authority; no H2 or mocks. Schema behavior is tested like `FinanceSchemaE2E`: a freshly migrated database per test (`PostgresTestDatabase.createMigrated()`), real commits, independent connections and autocommit for negative cases. SQL errors abort a PostgreSQL transaction, so each negative case runs in its own transaction. Fixtures insert a root (`finance.groups`, state `open`) and actors directly as the migration role or through the existing P3 test fixture.

| Test | Requirements | Setup and action | Expected observation | Level |
| --- | --- | --- | --- | --- |
| EXP-T01 | S01–S06 | Migrate a fresh database; also upgrade a database holding P3 roots/actors and data from other contexts. Inspect the catalog and Flyway history. | Tables, columns, types, nullability, defaults, PKs, unique key, all FKs (including `MATCH SIMPLE`), named checks and enum values as specified. PUBLIC has no `USAGE` on the three enums (`aclexplode` check as in `FinanceSchemaE2E`). No cascade, no cross-context FK, no extra index. Existing data unchanged. | E2E |
| EXP-T02 | S02–S04, S07 | As `sharewise_app`, insert a valid equal expense with two allocations (one share `0`) and its `expense/create` journal entry; add a correction version (new revision, same key) with copied shares and an `update` entry; add a deletion version (`deleted = true`) with a `delete` entry. Insert a `group/update` entry with `new_state = 'closed'` and `expense_key` NULL. Use the same revision number in a second group. | All rows accepted, including FK checks under the revoked `UPDATE`; earlier versions unchanged; the payer can also be a participant; the group entry passes despite the NULL expense reference; revisions are group-local. | E2E |
| EXP-T03 | S02, S03 | Same user ID as actor in group A and B. Expense in A with payer only in B; allocation in A for a participant only in B; allocation referencing a missing expense version; journal entry referencing an expense revision that does not exist; journal actor that exists only in B; journal entry in A pointing to an expense `(key, revision)` that exists only in B. | Each rejected with `23503`. No same-group bypass through shared user IDs. | E2E |
| EXP-T04 | S02–S04 | Duplicate participant per version; two expense versions with the same `(group_id, revision)` but different keys; duplicate journal `(group_id, revision)`; duplicate expense PK. | Each rejected with `23505`. | E2E |
| EXP-T05 | S01, S02 | Separately: empty and space-only name (tabs and control characters are application validation, since `btrim` trims spaces only), name of 129 characters ending in a non-space character (128 accepted; `varchar` silently drops excess trailing spaces), description of 1025 characters (1024 and NULL accepted), total `0` and `-1` (`Long.MAX_VALUE` accepted), revision `0`, invalid split strategy, NULL in each required column. | Each invalid case rejected with the corresponding error code; boundary values accepted. | E2E |
| EXP-T06 | S03 | Allocation amount `-1`; amounts `0` and `Long.MAX_VALUE`. | Negative rejected; boundaries accepted. | E2E |
| EXP-T07 | S05 | All 60 combinations of `subject × kind × expense_key (NULL/set) × new_state (NULL/provisioning/open/closed/archived)`, table-driven. Each combination uses its own revision r, with a pre-inserted expense version (G, K_r, r) whenever `expense_key` is set, so a wrongly accepted row cannot surface as `23503`. | Exactly the 6 valid combinations are accepted. Every other combination is rejected with `23514`, and the reported constraint is one the combination violates; combinations violating exactly one check name that check. No combination slips through because of NULL. | E2E |
| EXP-T08 | S02–S04 | Try to delete an actor, root or expense version referenced by allocations or journal entries (as migration role). | Rejected with `23503`; no cascade removes history. | E2E |
| EXP-T09 | S06, S07 | As `sharewise_app`: `SELECT` and `INSERT` on all three tables succeed; `UPDATE`, `DELETE`, `TRUNCATE` and DDL are denied (`42501`); `has_table_privilege` reports no `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES` or `TRIGGER`. P3 tables keep their previous privileges. | Append-only for the runtime role; schema objects owned by the migrator. | E2E |
| EXP-T10 | S08 | Regenerate from the migrated database; run the ordinary build and `ApplicationModuleStructureTests`. | `git diff` after regeneration is empty, so checked-in metadata matches the migration; no database or codegen needed for the ordinary build; module structure unchanged. | Generation review + Unit |
| EXP-T11 | Scope | Insert an expense version without allocations and journal entry, and one whose shares do not sum to its total. | Accepted at SQL level. The test documents that these invariants belong to P4.1b. | E2E |

### Acceptance gate

P4.1a is accepted when the migration and generated sources match this specification, `./mvnw verify` and the GitLab pipeline are green, and an independent review against this specification found no unresolved issues. The human verifies on a migrated local database (dev container or Compose stack) that the three tables exist and that the runtime role cannot update or delete history. There is no UI workflow. The persistence architecture and the Phase 4 foundation status describe only what is implemented. Human merge approval follows the workflow.

## Open questions and deferrals

P4.0 approval is a precondition (see Status), not an open question of this task.

| ID | Question | Proposal |
| --- | --- | --- |
| Q1 | Append-only history for the runtime role (EXP-S07) goes beyond the foundation text. | Included as a requirement for approval: one `REVOKE`, an existing precedent (`groups.invitations`), no effect on tests (fresh database per E2E test) or on P5 migrations. Reject explicitly during review if the team prefers convention only. |
| Q2 | Migration ordering with P2.4a (`platform/V202611251400`/`1410`) and future P5 changes to `change_records`. | Choose the timestamp when implementing, after the latest migration on `main`; P5 adds `settlement_key`, its FK, the enum value and the checks in a later migration. P4.1a reserves nothing for P5. |
| Q3 | Index on `allocations (group_id, participant_id)` for balance queries? | Not now. The foundation forbids indexes without a demonstrated need; P4.3b adds one if its query plan requires it. |
