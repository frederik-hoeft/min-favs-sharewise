# P2.0e - Landing-page design deliverable (agent brief)

**Status:** Draft for human review. This brief guides each design candidate and the selected landing-page implementation. Read it together with the [P2.0e architectural specification](specification.md) and [implementation plan](plan.md); it does not approve a design or authorize a new implementation phase.

## Objective

Create a polished, credible public-facing ShareWise landing page that also demonstrates a **coherent design direction for the wider application**. The page should introduce the product to a visitor while making it possible for the team to judge what the same visual language might look like in actual group and finance interfaces.

This is a design exploration, not a feature implementation or a contract for later screens. The landing page may remain in the product, change substantially or be removed once the real application exists. Its value now is a tangible, holistic design candidate rather than a permanent specification for business-page layouts.

## Product capabilities to communicate

A visitor should be able to understand these core ShareWise capabilities from the page as a whole:

- **Group management:** organizing groups and participants for shared costs.
- **Expense management:** recording shared expenses and how their costs are allocated among participants.
- **Settlements:** recording money exchanged **outside ShareWise** between participants; the application does not move or verify payments.
- **Balance overview:** understanding who is owed money and who owes money within a group.
- **Financial history:** reviewing recorded financial changes, including expense/settlement revisions and historical financial positions.

Refer to the [domain model](../../../data-model-design.md), [architecture](../../../architecture.md) and [task backlog](../backlog.md) for terminology, invariants and intended workflows. Do not invent business behavior to make a preview more dramatic. For example, distinguish a recorded external settlement from a payment performed by the application, and keep any displayed financial examples internally plausible.

**How these capabilities are presented is a design decision.** They can be conveyed through copy, illustrative in-app views, partial UI compositions, screenshots or other suitable treatments. Do not assume one screen, section or interactive demo per capability. However, the result should provide enough believable product-interface context to evaluate the design beyond marketing typography and decorative branding alone.

## Fixed technical and functional boundaries

- Use the agreed **Next.js App Router + React + TypeScript**, **Mantine** for standard UI controls and centralized theming, and **SCSS Modules** for page/component layouts and custom styling. Do not use Tailwind, Bootstrap or a competing component library.
- The **entire landing-page deliverable is statically exportable** and works when served as ordinary generated assets. It must not require a backend, live application data, Next.js runtime server, build-time network content, remote fonts or hosted runtime assets.
- Product previews use **local static assets or illustrative fixture data**. Interactive behavior is limited to genuine presentation needs such as working navigation and theme selection; depicted business controls need not operate and must not misleadingly imply that they do.
- Support coherent **light, dark and system** theme behavior. Deliver usable narrow/mobile and wide/desktop layouts with semantic structure, accessible navigation, visible keyboard focus, readable contrast and reduced-motion handling, following the [overall requirements](specification.md#requirements-and-invariants).
- Do not implement authentication, API clients, persistent finance state, expense or settlement calculations, or any future feature page as part of this deliverable. Mock screens and design references carry no commitment to a future functional layout.

## Creative latitude

Choose the visual identity, composition, content hierarchy, tone, typography, palette, imagery, density, and style of product previews. Make an honest effort to design a landing page someone might publish, **not an explicit component showcase, wireframe index or sequential storyboard**. The page should feel like one product rather than marketing imagery unrelated to the depicted application.

The brief intentionally does not prescribe a hero treatment, number of sections, exact copy, screenshots, arrangement of product UI, reusable component inventory, or future navigation structure. Favor a compelling and coherent result over checking off a fixed set of visual elements. Use Mantine defaults and theming where appropriate, with targeted SCSS for distinct compositions.

## Reviewable result

Each candidate provides a working static page and representative **mobile/desktop, light/dark** views for team comparison, alongside a short explanation of its visual direction and any notable constraints. Review considers landing-page credibility, coverage of the core capabilities, continuity between marketing and product previews, readability, accessibility, responsive behavior and static-only operation. Different candidates need not share the same copy, layout or preview screens.

A selected candidate is a **design reference**, not a frozen design system for later Group/Finance features. Final selection and integration require the human/team gates in the [plan](plan.md).
