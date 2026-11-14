# P2.0e - Implementation plan: frontend foundation and landing page

**Status:** Draft, pending human approval of the [P2.0e specification](specification.md) and this plan. Phases are proposals, not authorized or completed work. Follow the [AI development workflow](../../../development/ai-development-workflow.md): task artifacts are approved and committed to the authoritative task branch before implementation; each subsequent increment requires review before advancing.

## Baseline, dependencies and execution model

The current source snapshot contains Next.js 16 / React 19 / TypeScript, npm, Mantine, Sass/SCSS Modules, a neutral starter homepage and a standalone Next.js server/greeting proxy. The P2.0e target is **Mantine + SCSS Modules**, with a coherent landing-page and product-preview design determined later through candidate comparison. The intended production static export is described in the [stack overview](../../../stack/overview.md), but P8.1/P8.2 own replacing the server/proxy deployment. Avoid touching P1.x backend work or designing P1.4's future HTTP-client conventions.

Use the existing approved Git baseline and a distinct branch/patch for each coherent reviewed increment. Keep prototype alternatives isolated; only the selected design is merged into the P2.0e implementation line. Progress checkboxes are checked **only after verification and human approval**. Architectural changes return to the specification gate before the plan is revised. Every phase records commands, observed results, unavailable tooling and the precise patch baseline.

The supplied **Node-only** handoff matches Mantine and Sass in the current lockfile, but does **not** include the proposed Vitest/Testing Library/axe test dependencies. Adding those packages requires another refreshed offline bundle. Do not claim strict offline verification using an incomplete archive or silently switch to online install/system caches. The frontend's generated [Next.js instructions](../../../../src/frontend/AGENTS.md) require consulting the local version's documentation before code changes.

## Phase A - Fix the frontend technology stack and checks

**Covers:** FE-01, FE-02 and FE-07 foundations; FE-T01.

- [ ] Pin compatible Mantine core/supporting packages and Sass in `package.json` / `package-lock.json`; remove Tailwind packages, imports, classes and Turbopack integration while retaining a functioning starter frontend. Keep the stack independent of any candidate palette or page composition.
- [ ] Introduce the root Mantine stylesheet/provider, a minimal neutral theme configuration and SCSS Module conventions. Use supported Mantine APIs and Next.js App Router provider/client boundaries; do not build an application-specific control framework.
- [ ] Add focused React component testing with **Vitest, React Testing Library/user-event and axe-core** (or a documented compatible axe adapter). Add deterministic test and generated-type-aware type-check scripts, retaining the existing ESLint and Next.js production build.
- [ ] Update the frontend CI job to execute the new scripts, without weakening the backend jobs. Refresh the Node-only offline npm handoff from the locked dependencies and validate a clean restore/build using only the supplied tools/cache.
- [ ] Verify no transitive stylesheet or scaffold dependency is silently loading Tailwind; confirm tests, lint, type check and build pass. Record any limitations involving native modules or external services.

**Gate A:** The migrated frontend builds and the basic test harness runs reproducibly with the updated lockfile and complete offline cache. A human reviews the independent stack patch before design exploration begins. No page aesthetics are approved by this phase.

## Phase B - Prototype and select the visual direction

**Covers:** FE-03 through FE-06 and FE-10 design choices; FE-T04, FE-T06, FE-T09 and FE-T11 (candidate-level checks).

- [ ] Use the [agent-facing landing-page specification](landing-page-specification.md) as the common brief: a credible marketing page and coherent product concept covering group management, expense management, settlements, balances and financial history. Keep the **domain coverage and nonfunctional scope** comparable across variants without requiring identical text, layout, sections or preview screens.
- [ ] Create at least two **materially different** prototype candidates on isolated branches or reproducible snapshots, all using the approved Mantine/SCSS stack and static illustrative content. Give agents freedom to explore how product UI previews, imagery and marketing copy form a credible landing page; do not force a component gallery or feature-by-feature storyboard. Do not accumulate parallel themes in production code.
- [ ] Capture comparable mobile/desktop and light/dark views, note trade-offs (marketing clarity, credibility of feature previews, cross-app visual coherence, density, contrast, responsiveness, maintainability) and run basic keyboard/automated accessibility checks on each candidate. Review preview semantics against the domain model rather than validating actual business workflows.
- [ ] Have the team/human compare candidates and approve one visual direction or request a further iteration. Record the decision, approved reference views and concrete design tokens/typography/layout conventions in an appropriate design reference or implementation artifact, not as arbitrary requirements in this technology specification.

**Gate B:** A documented human/team decision identifies the visual direction. No candidate is treated as approved merely because an agent generated it or its tests passed. If the review changes the selected stack or architectural boundaries, revisit the specification before implementation.

## Phase C - Implement the approved landing-page foundation

**Covers:** FE-02 through FE-06, FE-09 and FE-10; FE-T02 through FE-T07 and FE-T11.

- [ ] Integrate the selected design as one cohesive shared app shell, real informational navigation, marketing content and **static, illustrative product previews** per the [landing-page specification](landing-page-specification.md). Replace template UI without implementing sign-in, group or finance workflows, or introducing live data dependencies. Keep preview-specific presentation components adjacent to their styles.
- [ ] Implement semantic design tokens and Mantine component defaults for the approved light/dark direction. Implement an accessible theme control with system preference, persistence, robust storage fallback and stable initial rendering/hydration.
- [ ] Factor reusable UI compositions only where the selected design demonstrates real reuse. Keep standard controls styled by Mantine and layouts by local SCSS Modules, with deliberate responsive breakpoints.
- [ ] Add behavior-oriented component tests for navigation, theme states and fallback, plus representative automated accessibility checks. Review keyboard navigation, visible focus, reduced motion, contrast, small/zoomed viewport behavior and the truthful distinction between previews and live functionality manually.

**Gate C:** The human reviews the full selected-design patch against the approved reference, including its product previews. Frontend checks pass and the entire landing page is usable in both themes without a backend; future business journeys remain unimplemented and unselected variants remain excluded.

## Phase D - Static-export evidence and frontend quality gate

**Covers:** FE-07 through FE-10; FE-T01, FE-T05, FE-T08 and FE-T10.

- [ ] Make frontend CI exercise lint, a generated-type-aware TypeScript check, component/accessibility tests and production build, including nonzero exit behavior on failures; avoid redundant scripts or a second build system.
- [ ] Verify the selected landing page and its UI dependencies in a clean **isolated `output: "export"` build**. If the current server-only `/api/greetings` route still blocks full export, exclude it only from the disposable probe. Disable incompatible Next.js prerendering/PPR options in that probe when necessary and document the limitation; do not claim P8.1 completion or remove P1.4's integration seam as a test workaround.
- [ ] Serve the generated static output without Node/Next.js runtime and exercise the page, navigation and theme control. Confirm illustrative previews render entirely from bundled assets/data, without backend requests, remote fonts/assets or server-only APIs. Keep any export-test harness minimal, reproducible and clearly separated from production deployment.
- [ ] Run full relevant frontend validation with the updated offline cache, inspect the complete branch diff and verify no backend or unrelated application files changed.

**Gate D:** CI and local checks provide reproducible evidence for the supported frontend stack and static landing-page export. Any remaining full-app server-route/deployment work is explicitly assigned to P8.1/P8.2, with no claim of production Nginx integration.

## Phase E - Reconcile documentation and perform acceptance

**Covers:** All FE requirements and tests; final human review and merge authorization.

- [ ] Reconcile the implemented stack and scripts in [stack overview](../../../stack/overview.md), [developer guide](../../../development.md), frontend README, CI/offline-handoff guidance and affected frontend instructions. Replace stale references to Tailwind as the selected UI stack while distinguishing starter and implemented capabilities.
- [ ] Record the approved design reference and explain practical theme/SCSS ownership in the appropriate steady-state developer/design documentation. Keep authoritative guidance out of task-only plans and avoid duplicating framework documentation.
- [ ] Confirm traceability between the overall specification, the [landing-page specification](landing-page-specification.md), test outcomes, chosen prototype and actual implementation. Run final frontend lint/typecheck/tests/build, offline restore and documentation link/format checks; review `git diff --check` and the complete task branch.
- [ ] Provide a manual acceptance script for mobile/desktop navigation, keyboard focus, system/light/dark modes, reload/persistence, domain-informed product-preview coverage and static-only behavior. Record human results, complete independent review when warranted and resolve findings as incremental patches.
- [ ] Only after final human authorization, reconcile the [P2.0e backlog item](../backlog.md#p20---shared-contracts-and-frontend-foundation) and merge the approved task branch. Do not mark incomplete P8.1/P8.2 deployment work or P1.x tasks as complete.

**Gate E:** The final reviewed source, design decision, validation evidence and authoritative documentation agree. Human acceptance and merge authorization are explicit; the baseline stays unchanged until then.

## Deferred work and coordination

P1.4 owns the frontend HTTP client and same-origin dev route; P2.1/P2.2 own interactive business journeys and their APIs; P8.1/P8.2 own removing the runtime Next.js server and deploying through Nginx. No implementation phase in this plan authorizes work in those areas. Separate candidate designs are evaluation artifacts and need not survive as product code after the selected direction is accepted.
