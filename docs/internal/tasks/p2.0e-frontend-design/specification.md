# P2.0e - Frontend design foundation and static landing page

**Status:** Draft for human review. The [implementation plan](plan.md) is also provisional; no implementation phase is authorized by this document. Follow the [AI development workflow](../../../development/ai-development-workflow.md) and the repository's [documentation](../../../../.agents/skills/documentation/SKILL.md) and [engineering-quality](../../../../.agents/skills/engineering-quality/SKILL.md) guidelines.

## Purpose and boundaries

P2.0e establishes a maintainable frontend component/styling foundation and a **fully static ShareWise landing page** that serves as both a credible public-facing introduction and a demonstration of a coherent, application-wide design concept. The [Phase 2 backlog](../backlog.md#p20---shared-contracts-and-frontend-foundation) owns the scope; the [stack overview](../../../stack/overview.md) owns the intended static-browser/Nginx/API architecture. This specification fixes the **technology and responsibility boundaries**, but deliberately does **not** prescribe a visual design. Palette, type scale, composition and other aesthetic decisions are selected through reviewable design prototypes after the dependencies are usable.

The supplied starter uses Next.js 16, React 19, TypeScript, npm, Tailwind 4 and a Next.js server-side greeting proxy. Its homepage is template content; it has no accepted component system, design tokens, theme toggle or frontend component-test harness. Production static export and Nginx routing are targets, not current behavior. The frontend work is independent of P1.x backend changes still in review.

P2.0e includes library/tooling migration, design exploration and selection, a responsive landing page with illustrative product previews, a shared shell, light/dark/system themes, and proportionate frontend checks. Previews may depict group and finance capabilities through static examples but do not implement those workflows. The task excludes registration, authentication, functional group/expense/settlement/history pages, live business data, backend/API changes and production deployment migration. P1.4 owns the API-client and same-origin development route; P8.1/P8.2 own full static-export/Nginx deployment and hardening.

## Architectural solution

### UI stack and styling ownership

- **Next.js App Router, React, TypeScript and npm** remain the frontend framework and package manager.
- **Mantine (`@mantine/core` with its required supporting packages)** provides standard accessible controls, interactions and default visual styling. Prefer Mantine components and supported APIs over custom reimplementations or DOM-level overrides.
- **Mantine theme configuration** centrally owns component defaults, semantic colors, typography, spacing, radii and light/dark color schemes. Components and custom styles consume shared theme tokens, including Mantine CSS variables, instead of inventing disconnected palettes or sizing constants.
- **Sass/SCSS Modules** (`*.module.scss`) own custom layout, composition and application-specific styling. Keep styles adjacent to their owning component or page; use ordinary SCSS nesting and mixins when beneficial. Global styles are limited to library imports, document-level styling and truly global rules. Component overrides use Mantine's public styling APIs or centrally defined theme extensions.
- **Tailwind and its build integration are removed** as starter-only dependencies; Bootstrap and shadcn/ui are not introduced. The chosen separation favors pre-styled controls, centralized theming and locally owned layouts over per-element utility-class composition.

The first dependency migration is not an excuse to introduce a custom component framework. Reusable components are added for meaningful application-level composition or repeated behavior; Mantine itself remains the owner of ordinary controls.

### Component and runtime boundaries

The root layout installs Mantine's stylesheet, provider, theme configuration and color-scheme initialization. Static page content and navigation remain renderable during the build; narrowly scoped client components own interactive browser behavior, especially the theme control. The app shell owns consistent site structure and navigation; landing-page sections own their content and layout; reusable UI components are independent of specific pages. Avoid client-marking the full tree merely because one child is interactive.

Theme selection supports **light, dark and system preference**. The displayed mode and toggle state remain consistent, including initial hydration; explicit user choices persist across navigation/reload where browser storage is available. Storage restrictions or invalid stored values must not prevent the page from rendering. No theme or landing-page operation requires an application backend, Next.js runtime server, external font fetch or remote asset host.

The landing page communicates ShareWise's intended purpose accurately: tracking group expenses and settlements of payments made outside the application, without suggesting ShareWise transfers money or enforces debts. It combines normal landing-page communication with credible, domain-informed product previews; these may be illustrative UI compositions or static images, but must not pretend to be working business features. Actual page/section navigation and theme controls function without a backend. The chosen design supplies a shared shell and coherent presentation, not an explicit component gallery or a storyboard of future feature screens.

The static-export target is **browser-rendered interactivity over build-generated assets**, with future same-origin `/api/*` requests served through Nginx to Spring Boot. The starter's server-side `/api/greetings` route and `output: "standalone"` currently prevent a full-repository export. P2.0e must prove that its **own pages and dependencies** can be exported without a Next.js runtime server, using an isolated build/probe if the legacy route remains. This proof must not masquerade as completion of P8.1 or silently delete/replace the P1.4 development/API integration boundary.

### Landing-page design deliverable

The [agent-facing landing-page specification](landing-page-specification.md) defines the creative brief and deliverable-level constraints. It requires a holistic visual concept encompassing ShareWise's core business cases: **group management, expense management, settlements, balance overview, and financial history**. The [domain model](../../../data-model-design.md) and other repository documentation supply the semantics; the brief does not duplicate them or specify the future page architecture.

All previews use static, illustrative content or assets and require **no backend, live data, runtime Next.js server or external content service**. Their value is to explore how ShareWise's marketing and business-facing interfaces could share a visual identity. They are not authoritative designs for later feature work and need not be retained after P2.0e.

### Visual-design selection process

Aesthetic choices are intentionally deferred. Once the UI stack is installable and validated, produce **multiple materially different, reviewable design candidates** against the common [landing-page brief](landing-page-specification.md), component capabilities and quality constraints. Candidates may vary content, layout, typography, density, palette, imagery, brand treatment and which product UI concepts they visualize; comparable **business coverage and evaluation conditions** matter more than identical sections or mock screens. Each must still demonstrate mobile/desktop behavior and light/dark modes. Iterate quickly using agent-generated prototypes, with explicit human/team comparison before incorporating one direction into the shared foundation.

This is **A/B comparison of design candidates**, not production traffic splitting, user tracking or a mandate to run a statistically powered experiment. Keep unselected variants isolated from the main application and avoid accumulating competing theme/component implementations. Record the selected direction and its concrete tokens in the implemented theme/design reference after approval; material changes to the component or runtime architecture return to specification review.

## Requirements and invariants

| ID | Requirement / observable contract |
| --- | --- |
| FE-01 | Frontend dependencies and build configuration use Mantine and Sass/SCSS Modules, without Tailwind, Bootstrap or duplicated styling frameworks. `npm ci` remains reproducible from the committed lockfile. |
| FE-02 | Standard controls inherit coherent defaults from one Mantine theme. Component/page SCSS is locally scoped; global selectors and cross-component overrides are exceptional and deliberate. |
| FE-03 | Light, dark and system preference produce coherent, accessible color schemes. The theme control is operable by keyboard and assistive technology; preference persistence and hydration do not produce a misleading initial state. |
| FE-04 | Shared shell, navigation and static landing content use semantic landmarks and headings, meaningful control labels, functioning destinations and truthful product descriptions. Only genuine navigation and presentation controls are operable; unimplemented business features are not presented as working. |
| FE-05 | The landing page and representative components adapt to narrow and wide viewports without unintended horizontal scrolling, clipped controls or inaccessible navigation. Keyboard focus is visible; reduced-motion preferences are respected. Aim for WCAG 2.2 AA color contrast and interaction behavior. |
| FE-06 | A repeatable candidate comparison evaluates **coherence across marketing and representative product interfaces** and results in a human-approved visual direction before final styling is integrated. The specification fixes design-system responsibilities, not arbitrary colors, fonts, section structure or marketing copy. |
| FE-07 | Frontend CI runs lint, generated-type-aware TypeScript checking, production build and focused component/accessibility tests. Checks fail on regression; build scripts do not rely on remote fonts or services. |
| FE-08 | The P2.0e landing page and chosen UI dependencies are statically exportable. Any remaining server-only starter/API route is identified separately; production Nginx migration is deferred to P8.1/P8.2. |
| FE-09 | Changes stay in the frontend and its documentation/tooling boundaries; P1.x backend work, authentication, API transport and business modules remain unaffected. |
| FE-10 | The landing page conveys group management, expense management, settlements, balance overview and financial history as a coherent product story, with believable domain-informed previews where useful. Every preview uses static content/assets, has no backend or runtime-server data dependency, and is explicitly nonfunctional where it resembles an unfinished product screen. There is no required number or arrangement of screens; previews are nonbinding design explorations. |

Accessibility is a cross-cutting acceptance constraint, not a candidate-specific aesthetic preference. A design that cannot satisfy the interaction and responsive requirements is not eligible for selection, regardless of appearance. Tests exercise behavior and public interactions rather than internal theme implementation details.

## Core verification and acceptance cases

The implementation phase adds narrower unit tests as appropriate. These cases establish the minimum task-level evidence; accessibility automation supplements, but does not replace, manual keyboard and visual inspection.

| Case | Setup and action | Expected outcome / evidence |
| --- | --- | --- |
| FE-T01: dependency migration | From a clean checkout with a compatible dependency cache, run locked npm install, lint, type check, tests and production build. | All checks pass with no Tailwind runtime/build imports; manifest and lockfile agree. Strict offline restoration is demonstrated once the refreshed handoff is available. |
| FE-T02: default and persisted theme | Open the landing page with light and dark OS preferences; toggle explicitly, reload and navigate. | Each theme renders consistently, chosen preference persists where available, and there is no hydration/theme flash or wrong toggle state. |
| FE-T03: unavailable preference storage | Block or corrupt browser storage, then load and operate the theme control. | Page and controls remain usable, with a deterministic fallback and no uncaught exception. |
| FE-T04: responsive extremes | Check representative narrow mobile and desktop viewports, including zoomed text and long labels. | Content, controls and navigation remain usable with no unintended horizontal overflow or overlap. |
| FE-T05: keyboard and assistive interaction | Navigate using keyboard only, operate theme toggle and navigation, inspect landmarks and labels. | Logical focus order, visible focus, correct accessible state/labels and no keyboard trap; automated axe checks report no actionable violations in representative states. |
| FE-T06: light/dark design quality | Inspect the selected candidate's normal, hover, focus and disabled/control states in both themes. | Readable typography and accessible contrast; reduced-motion handling and reusable component styling remain coherent. |
| FE-T07: truthful static navigation | Open page without a backend and follow actual page navigation/actions; inspect any illustrative product controls. | Informational routes/anchors work; mock business actions are not falsely interactive; no dead links, invented features, remote assets or API requirements. |
| FE-T08: static-export compatibility | Build a clean, isolated export probe containing P2.0e routes/components while accounting for the legacy server-only greeting route. Serve the emitted files without Node. | Landing page, navigation, styling and theme control work from static assets. Report any residual full-app export blockers explicitly. |
| FE-T09: candidate review | Present comparable variants at mobile/desktop sizes and in both modes to the team, including their product-preview treatments. | Candidate differences in marketing/product coherence and accessibility are visible; the chosen direction receives human approval before final integration; rejected variants do not enter the shared component tree. |
| FE-T10: regression scope | Examine source diff and CI changes against the approved baseline. | No backend/API/security changes or unrelated rewrites, and every plan phase has traceable test/review evidence. |
| FE-T11: product-story coverage | Review the page and its previews against the [landing-page specification](landing-page-specification.md) and the repository domain model. | All five core capabilities are understandable without a prescribed screen-per-feature structure; illustrative amounts, balances and settlement descriptions are internally plausible, and no preview claims real payment execution, live data or finalized future UI. |

Manual acceptance consists of opening the chosen landing page at narrow and wide widths, checking keyboard navigation and focus, switching system/light/dark themes, reloading, and exercising its actual links. Review the product-story coverage and credibility of static previews without treating them as functional workflows. Compare the implemented page with the approved candidate; separately inspect the isolated static-export result and note the retained starter-route limitation.

## Dependencies, deferrals and review gate

Mantine, Sass and the frontend test packages are **not present in the currently supplied Node-only offline dependency handoff**. Lockfile changes require a fresh, reproducible npm cache/toolchain validation under the [offline development workflow](../../../development/chatgpt-development-workflow.md); do not weaken strict-offline checks or substitute unrelated caches. An offline Java/Maven backend handoff is unnecessary for this frontend-only task.

The [plan](plan.md) sequences dependency migration, candidate exploration, selected-design implementation, quality/static-export checks and final documentation reconciliation. P1.4 owns subsequent HTTP/client conventions, P2.1/P2.2 own actual authenticated business journeys, and P8.1/P8.2 complete static production deployment. The documentation and plan require approval before implementation; the visual direction receives a **separate team/human gate** before it is finalized.
