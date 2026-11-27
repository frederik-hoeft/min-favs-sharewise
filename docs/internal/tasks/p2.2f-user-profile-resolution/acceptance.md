# P2.2f - Implementation acceptance

**Status:** Aggregate self-review complete, pending maintainer acceptance. The implementation is based on main `9a2222e213ad67b56d437664f8a7254901c3d318` and the approved [specification](specification.md). The [plan](plan.md) records the phase checkpoints and the supplied generated-schema handoff. Gate E is not accepted; Phase F and merge remain unauthorized.

## Evidence available for review

| Scenario | Evidence | Remaining evidence |
| --- | --- | --- |
| UP-T01 | Upgrade and fresh-schema tests compiled. Maintainer supplied actual generated metadata and reported 233 E2E tests, one failure in the obsolete clear-name scenario at the intermediate migration checkpoint. | Integrated PostgreSQL upgrade/fresh-schema execution and generated-source freshness. |
| UP-T02 | Domain validation, HTTP contract and Unit/Modulith checks pass. Legacy clear-name scenario replaced; registration rollback and mandatory profile tests compile. | Actual PostgreSQL registration, update, rollback and concurrency execution. |
| UP-T03 | HTTP admission, minimal projection, bounded lookup and failure tests pass; PostgreSQL lookup tests compile. | Actual PostgreSQL query and integrity tests; live HTTPS verification. |
| UP-T04 | Component and transport tests pass for registration, password confirmation, login and mandatory account names. | Actual browser flow, including automatic name suggestion and manual override. |
| UP-T05 | Resolver policy and lifetime tests pass, including overlapping work, expiry, failure, cancellation, stale response and session replacement. Account response validation and cross-consumer rename publication have dedicated regressions. | Actual browser navigation, renewal, rename and session replacement. |
| UP-T06 | Deterministic identity and Group component tests pass, including duplicate names, full suffix extension, accessible labels, retry and exact mutation target. | Rendered geometry, contrast and interaction across widths/themes. HSL contrast is additionally checked numerically. |
| UP-T07 | Deployment contract tests and updated smoke registration/lookup/rename fixtures; 12 authorization E2E cases discovered. | Clean disposable HTTPS smoke, actual deployed authorization E2Es and human browser checks. |

## Integrated verification

The supplied offline toolchains and caches were used without dependency substitutions. Archive checksums and wrapper/lockfile hashes matched; the existing Maven POM and npm package descriptor hashes differ from the handoff manifests. OS-level network isolation was not independently established. PostgreSQL and Docker are unavailable locally. Chromium blocked the local static fixture URL by administrator policy before the page loaded; no visual acceptance is claimed.

| Check | Command / result |
| --- | --- |
| Backend Unit/Modulith | `mvn -B -ntp -o -Dmaven.repo.local="$MAVEN_REPO" -Punit-tests -DtestParallelism=16 clean verify`: 461 passed, no failures/errors/skips; all test sources compiled, packaging passed. |
| Frontend | `npm run test`: 434 passed in 32 files (after Gate E UI follow-up); `npm run lint`, `npm run typecheck`, `npm run test:typecheck`, `npm run build`, `npm run build:static` passed. |
| Static export | `npm run test:static-export`: passed. |
| Deployment contracts | `python3 -m unittest discover -s deploy/tests -v`: 86 passed. |
| E2E discovery | `node_modules/.bin/vitest list --config tests/vitest.e2e.config.mts --json`: 12 cases discovered, not executed. |
| Source and patch integrity | Source encoding, executable script modes, baseline-relative diff and independent patch replay checked. |

The maintainer requested UI refinements during Gate E; the [plan](plan.md#phase-e---aggregate-verification-and-human-acceptance) records the changes and reruns. A fresh Chromium navigation attempt timed out without a rendered page. The 4,096-UUID numerical color regression passed independently of browser rendering; visual acceptance of the revised layout remains outstanding. The backend and deployment contract results above are from the preceding Phase E checkpoint, whose backend/deployment files are unchanged.

No dependency changes, new authorization profiles or Group/Finance schema changes were introduced. The supplied jOOQ patch remains unchanged after its separate handoff checkpoint; compilation and metadata assertions are not a substitute for generating against the integrated migrated database.

The subsequent roster-overflow correction bounds the grid column independently of its content. All 51 targeted identity and Group tests, the static build and static-export probe passed; confirm the long-name case in both the desktop aside and mobile Drawer without horizontal scrolling. No backend or API changes.

## Maintainer acceptance checklist

Use the integrated checkpoint (including the generated IAM metadata), not the migration-only checkpoint. Run the existing [PostgreSQL test profiles](../../../development/tests.md#run-the-suites) and [code generation freshness check](../../../development.md#database) with the normal dev-container or CI configuration. Record the actual Failsafe counts/results for Integration and E2E separately. The independent jOOQ CI job must confirm generated metadata is fresh. In particular, verify the migration rollback and account/profile creation rollback scenarios and the replacement of the obsolete clear-name test.

- [ ] PostgreSQL Integration and E2E pass, including the upgrade scenarios, and jOOQ freshness is confirmed.
- [ ] Required CI pipeline gates pass at the integrated revision.
- [ ] From `deploy`, run `./scripts/test-compose.sh --browser` according to the [deployment runbook](../../../../deploy/README.md); confirm the HTTPS profile and Group lifecycle smoke and the deployed authorization E2E suite. Exercise migration of existing accounts separately from fresh-stack testing.
- [ ] Register two accounts with the same display name, verify email-derived suggestions stop after manual editing or clearing and password mismatch prevents submission, join the same group, and verify names, avatars and secondary IDs distinguish members in management and confirmation. Confirm role changes/removal affect the selected membership only.
- [ ] Verify own name update, navigation/cache reuse, failed lookup fallback/retry, invitation continuation, bearer renewal and logout/account replacement. Use a migrated account to check its generated name and ability to rename.
- [ ] Inspect desktop, intermediate and mobile widths in light and dark themes, including ellipsized long names, visible self markers/role badges, centered avatars and option controls, full names in confirmation dialogs, long secondary identifiers, keyboard navigation and readable avatar contrast. Record findings and any needed corrections.
- [ ] Maintainer accepts implemented behavior and authorizes Phase F documentation reconciliation. Independent review and explicit merge authorization are separate requirements.

Do not deploy the migration with the old backend/frontend. Stop writers, back up persistent state, migrate and deploy matching application versions as a coordinated release. This is an application-maintained exactly-one profile invariant with database-enforced non-nullability and profile-to-account PK/FK constraints, not a database-enforced reverse foreign key.
