# P3.1 - FinancialGroup and FinancialActor persistence/provisioning: implementation plan

**Status:** Approved implementation sequence following [specification](specification.md) approval. Checkboxes denote verified and human-approved work, not merely prepared patches. Follow the [AI development workflow](../../../development/ai-development-workflow.md) and [local patch exchange guide](../../../development/chatgpt-development-workflow.md).

## Baseline and prerequisites

- [x] Maintainer approves the P3.1 specification and plan and authorizes Phase A with Finance-local revision domains.
- [x] Maintainer approves the shared [Phase 3 foundation](../../integration/phase-3-foundation.md) (P3.0); accepted P3.1 artifacts are part of the maintainer’s generated-source handoff baseline (`c00e925135bc262f726d4a0feadb088d9a75ea4b`).
- [x] Confirm the accepted baseline contains the P1 persistence/transaction foundation. The supplied 2026-11-24 snapshot has a clean local import and passed 342 Unit tests with the Maven-only offline handoff. P2 code, messaging and attestation implementation are not prerequisites.
- [ ] Coordinate the globally ordered Flyway timestamp with the P2 owners before branch integration.

Work on a local P3.1 feature branch; exchange patches against the last mutually accepted content, not the independent Git commit hashes. The planned implementation has one agent patch for each backlog subtask, a maintainer code-generation patch between them, and one consolidated documentation patch. Review fixes remain incremental against the last exchanged state and do not advance the phase. Use `P3.1: Specify Finance persistence and implementation plan` for these initial artifacts and the commit messages below on both histories; record the exact authoritative revision for review and acceptance. Keep each patch buildable except that actual database verification runs in the connected development environment.

## Phase A - P3.1a: Finance migration and generation configuration

- [x] Implement FIN-S01–S05 as a single additive Finance-owned migration under `db/migration/finance/`, named according to `V<yyyyMMddHHmm>__finance_<description>.sql`. Reuse the platform schema/default privileges; do not edit historical migrations. Coordinate the version against the latest authoritative branch before push/merge: Flyway `outOfOrder` stays disabled. If an unmerged migration must be re-timestamped, coordinate before integration and reset disposable databases as needed; never silently rewrite a migration already applied to a shared environment.
- [x] Add the opt-in Finance jOOQ profile (FIN-S06), aligned with the existing identity configuration, and the closed Finance module declaration with an empty dependency whitelist. The generated package will cause Finance to be discovered by Modulith, so its declaration must precede the generated-source handoff. Keep ordinary build dependencies and lifecycle unchanged; inspect whether configuration reuse is worthwhile without refactoring unrelated IAM code. Add PostgreSQL schema/constraint/trigger/role tests FIN-T01–T05 using the disposable PostgreSQL database fixture directly, without starting a Spring context or depending on generated Finance classes. FIN-T02/T03 use independent autocommit statements so constraint failures do not poison a shared transaction.
- [x] Run the offline Unit build (including test compilation), inspect the migration and tests, run `git diff --check` and export one implementation patch for review. The POM change invalidates the recorded descriptor checksum even if ordinary build dependencies are unchanged; the ordinary offline build remains green using only the supplied cache. No regeneration of the identity schema is required.
- [ ] Refresh the Maven-only handoff using the [offline handoff procedure](../../../development.md#portable-offline-dependency-handoff) to match the changed POM descriptor before claiming full handoff compatibility; the existing isolated cache continues to support the ordinary Unit build.

**Suggested commit:** `P3.1a: Add Finance schema migration and jOOQ generation profile`

**Gate A1:** Human reviews the migration, tests and profile, resolves review findings, applies the migration to a fresh and representative upgraded disposable PostgreSQL 18 database, and executes FIN-T01–T05. Accepted by the maintainer, who reports `mvn clean verify` green following generation at `c00e925135bc262f726d4a0feadb088d9a75ea4b`. The default Maven configuration includes Unit and database suites; detailed maintainer logs and manual acceptance evidence were not supplied.

### Maintainer generation handoff (part of P3.1a)

- [x] Generate Finance jOOQ sources against the migrated database using the existing development credentials/connection properties. From `src/backend/sharewise` in the connected devcontainer:

```bash
./mvnw -Pjooq-codegen-finance \
  -Djooq.codegen.jdbc.url=jdbc:postgresql://postgres:5432/sharewise \
  -Djooq.codegen.jdbc.user=sharewise_migrator \
  org.jooq:jooq-codegen-maven:generate
```

- [x] Review the generated diff for the Finance schema only (tables, records, enum, keys and indexes as generated), run the ordinary Unit build, commit and return the generated-source patch and verification results to the agent. Use a fresh migrated schema and the POM's generator version. Do not hand-edit generated files or add secrets to the patch.
- [x] Apply the maintainer patch to the same local task state; verify exact source correspondence, regenerated metadata consistency by review and offline ordinary build (FIN-T06). Actual code generation is a maintainer-run check, not performed in the offline workspace. The returned patch contains only Finance-generated metadata; the offline ordinary build at the imported state passed 342 Unit tests.

**Suggested commit:** `P3.1a: Generate Finance jOOQ schema metadata`

**Gate A2:** The maintainer accepted P3.1a and supplied the generated-source patch and green `mvn clean verify` result, authorizing Phase B. If parallel P2 changes require updating the baseline, exchange the exact accepted delta or a fresh source snapshot rather than guessing at the changes.

## Phase B - P3.1b: Finance root persistence port and adapter

- [ ] Extend the closed Finance module whitelist only with the shared named interfaces actually consumed; define the Finance-owned immutable root projection and lifecycle enum and the internal `FinancialGroupStore` port. Implement `ensureAndLock` and `lockExisting` through an operation-scoped jOOQ adapter using generated Finance metadata and the existing `JooqAccess` guard. No application service or actor adapter is added. Implement FIN-R01–R07, including the explicit typed provisioning state, transaction preconditions, failure propagation and no-update behavior for existing roots.
- [ ] Add focused real PostgreSQL Integration/E2E cases FIN-T07–T13 using the existing operation/transaction fixtures and independent E2E connections for locking, rollback and visibility. Extend the existing Modulith architecture test as appropriate; do not duplicate its verifier. Add Unit coverage for any meaningful pure mapping or validation behavior rather than mock-based claims about SQL semantics.
- [ ] Run the offline Unit/architecture gate, inspect the complete change and export one implementation patch. Human executes the PostgreSQL suites and reviews the port contract and concurrency evidence. Fix any findings before advancing.

**Suggested commit:** `P3.1b: Add transaction-bound Finance group provisioning and locking`

**Gate B:** Human accepts the implementation and actual PostgreSQL concurrency/rollback results. The port is ready for P3.2a/P3.4 without any P2 dependency. The typed not-ready *application outcome* remains the responsibility of future financial operations; the port's typed lifecycle state and absence are established here.

## Phase C - Consolidated steady-state documentation

- [ ] Reconcile `docs/architecture/persistence.md`, `docs/architecture.md`, `docs/data-model-design.md`, `docs/stack/persistence.md` and `docs/development.md` where affected. Describe actual schema ownership, the root lock contract, the Finance generation command and offline boundaries; search for other stale statements about Finance tables/modules. Keep shared event contracts in the Phase 3 foundation and planned P3.2/P3.4 behavior clearly distinct. Avoid repeating the task spec in durable docs.
- [ ] Update plan progress and acceptance evidence only for verified, approved gates. Link the specification/plan from the backlog if helpful, but leave completion checkboxes unchecked until the final acceptance gate. Verify relative links, formatting and the full task diff.

**Suggested commit:** `P3.1-C: Reconcile Finance persistence and development documentation`

**Gate C:** Human accepts the consolidated documentation patch and complete integrated task diff.

## Verification commands and final acceptance

Agent-side ordinary backend gate (from `src/backend/sharewise`, using the supplied isolated tools and cache):

```bash
mvn -B -ntp -o "-Dmaven.repo.local=$MAVEN_REPO" -Punit-tests clean verify
git diff --check
```

Human-side PostgreSQL and deployment checks (in the configured development environment):

```bash
cd src/backend/sharewise
./mvnw -B -ntp -Punit-tests clean verify
./mvnw -B -ntp -Pintegration-tests clean verify
./mvnw -B -ntp -Pe2e-tests clean verify
cd ../../../deploy
./scripts/test-compose.sh
```

Use focused `-Dit.test` during development if useful, but run applicable full suites before acceptance. Existing PostgreSQL setup and test isolation come from the [test guide](../../../development/tests.md). The offline workspace cannot run the database suites, generation or Compose smoke; record these as unexecuted locally, not as successes. No frontend change or standalone frontend build is in scope; deployment smoke validates the running integrated stack. The maintainer records actual commands, tested revision, environment and results below or links equivalent durable MR evidence.

| Evidence | Result |
| --- | --- |
| Specification/plan and P3.0 approval | Approved by maintainer |
| Offline Unit/architecture gate (implementation) | P3.1a and generated-source baseline: 342 Unit tests passed locally; P3.1b: offline `-Punit-tests clean verify` passed 344 Unit tests (including Modulith and test-category checks), with all database test sources compiled; human review pending |
| Fresh/upgrade migration, runtime role, manual database acceptance | P3.1a automated suite: maintainer reports green `mvn clean verify` at `c00e925135bc262f726d4a0feadb088d9a75ea4b`; separate manual acceptance and detailed environment evidence pending |
| Finance jOOQ generation and ordinary build | Generated-source patch `c00e925135bc262f726d4a0feadb088d9a75ea4b` accepted; maintainer verification green, offline 342 Unit tests passed |
| PostgreSQL Integration and E2E suites, including concurrency | P3.1b adds 3 Integration and 8 E2E tests; compiled locally but not executed without PostgreSQL; maintainer verification pending |
| Disposable deployment smoke | Pending |
| Independent MR review and resolutions | Pending |
| Human final diff review and merge authorization | Pending |

- [ ] Perform independent MR review of the integrated revision against the specification; resolve or explicitly justify findings. Re-run affected checks and reconcile docs when corrections change behavior. Suggested follow-up commit: `P3.1b: <specific review fix>` (use the affected subtask ID, or `P3.1: <message>` for cross-task corrections).
- [ ] Record manual database acceptance (fresh/upgrade migration, provisioning defaults, runtime privileges, orphan rejection using disposable data) and successful local deployment smoke, or a documented human-approved smoke exception with alternative evidence and deferred verification. No manual UI scenario applies.
- [ ] After all gates pass, mark P3.1a/b and P3.1 complete in the backlog, record final acceptance, and obtain explicit merge authorization. Suggested final commit: `P3.1: Record acceptance and complete Finance persistence tasks`. Merge the local task branch into the local approved baseline only after authorization; the human owns the authoritative merge.
