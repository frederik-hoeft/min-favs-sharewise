# ShareWise visual candidate 3: Warm Ledger

**Status:** Phase B design candidate, not an approved ShareWise design system. This document describes the implemented prototype. Team selection, production integration and future finance screens remain open.

## The visual premise

**Warm Ledger** treats group finances as an extension of everyday life rather than a banking product. Its character is editorial, reassuring and deliberate: expressive serif headlines, quiet ledger-like labels, generous warm-neutral space, deep pine surfaces and a distinctive citron accent. Pale peach introduces human, off-app interactions such as recorded settlements. Strong typography carries the public story; the same color and spacing vocabulary continues into a denser application workspace preview.

The page says what ShareWise does, without promising payment execution. The application overview is a fictional group called **Weekend in Copenhagen**; expense allocations, recorded external settlement, net-zero balances and change history all refer to that *same* example. The preview is not a screenshot of working business features, and is deliberately marked as a concept.

No third-party image or font service is used. The hero orbit is CSS artwork, its letter and symbols decorative. Fonts use local fallbacks, not bundled proprietary font files. The result is deliberately more expressive than the planned financial application itself; the editorial treatment is concentrated in landing-specific sections and public marketing headings.

## Tokens and semantic roles

`src/frontend/lib/theme.ts` is the single source for Mantine colors, typography, spacing/radii, standard component defaults, and the `cssVariablesResolver` roles. Color values below are hex sRGB. Use `var(--sw-...)` in SCSS Modules for semantic application surfaces, and Mantine theme colors/spacing for standard controls. Do **not** recopy these hex values into component styles to restyle the same responsibility.

| CSS variable | Light | Dark | Use |
| --- | --- | --- | --- |
| `--sw-page` | `#f7f7f0` | `#101c19` | Site canvas |
| `--sw-ink` | `#173a30` | `#e9f2e8` | Primary text |
| `--sw-muted` | `#65766d` | `#a5b8ae` | Secondary text |
| `--sw-line` | `#dce3d8` | `#34483d` | Borders, dividers |
| `--sw-surface` | `#ffffff` | `#192b25` | Plain cards, input background context |
| `--sw-surface-soft` | `#eff2e9` | `#21352d` | Soft panels, chart tracks |
| `--sw-accent` | `#c6de78` | `#c6de78` | Citron accent/artwork |
| `--sw-accent-ink` | `#1c3527` | `#1c3527` | Text on citron |
| `--sw-display-accent` | `#3e6a54` | `#c6de78` | Emphasized display typography |
| `--sw-peach` | `#f3d6c4` | `#604235` | Settlement editorial block |
| `--sw-peach-ink` | `#593d31` | `#ffe2ce` | Text on peach |
| `--sw-positive` | `#2f7556` | `#a6dfa9` | Positive group position |
| `--sw-negative` | `#a15c48` | `#eab39c` | Negative group position |
| `--sw-panel` | `#173b32` | `#263e33` | Immersive dark product section |
| `--sw-panel-ink` | `#f6f7eb` | `#f5f7ea` | Text on immersive panel |

`pine[0..9]` and `citron[0..9]` are the actual Mantine colors in `theme.ts`. `pine` is the `primaryColor`, with light-mode shade 8 and dark-mode shade 4; `autoContrast` is enabled. Mantine supplies color-appropriate states for Buttons, ActionIcons, Badges and menus, instead of hand-reimplementing their internals. A few fixed colors within the decorative hero and the mock workspace sidebar are *composition-local illustrative artwork*, not new general-purpose semantic roles. The fixed mint avatar palettes represent people, not financial state.

The theme exposes `--sw-content-width: 1180px` and `--sw-editorial-font`; Mantine owns base spacing (`xs=8px`, `sm=12px`, `md=16px`, `lg=24px`, `xl=32px`) and radii (`xs=4px`, `sm=8px`, `md=12px`, `lg=18px`, `xl=28px`). Landing page section padding, dense preview type sizes and the orbit geometry remain local where their one-off composition requires it.

### Type hierarchy

- Body/UI: `Avenir Next`, `Segoe UI`, Arial, sans-serif. Labels are small, bold, tracked uppercase; detailed finance views use legible sans-serif values.
- Editorial display: `Iowan Old Style`, Baskerville, Georgia, serif. Large headlines favor relaxed weight, tight spacing and italic emphasis. `h1`/`h2` theme levels define reusable starting sizes, while the landing page scales its headline specifically.
- Figures: aligned using stable spacing and units (e.g. `€164.00`); positive/negative amounts use the semantic roles, not saturated green/red fills.
- Code/technical numbers: `SFMono-Regular`, Consolas, monospace if future UI needs them.

## Standard controls and composition ownership

`MantineProvider` in `app/providers.tsx` provides the theme and resolver. `app/layout.tsx` includes `ColorSchemeScript defaultColorScheme="auto"` before content, matching `MantineProvider`. Mantine's built-in local-storage manager persists `auto`, `light` or `dark`, validates stored values, and falls back when storage access fails. Color scheme selection is handled by the small client-only `components/appearance-menu.tsx`. The page itself stays a Server Component.

The theme sets these application-wide defaults through supported Mantine extension APIs:

| Component | Default |
| --- | --- |
| `Button` | `md`, `xl` radius; uses `pine` unless a caller intentionally selects another role |
| `ActionIcon` | `xl` radius, `subtle` variant |
| `Card` | bordered, `lg` radius, `lg` padding |
| `Menu` | `md` radius, `md` shadow |

Use standard Mantine controls as controls: the mock finance workspace uses *plain spans and divs* for decorative mock navigation/actions so that a keyboard user cannot confuse them with operational features. Links in the real site header/footer and the hero CTAs have actual local anchor targets. No mock action is wired to an API or placeholder route.

**Reusable application patterns** live under `src/frontend/components/`: `Brand` (wordmark), `SiteHeader`, `AppearanceMenu`, `MobileNavigation`, `MemberAvatar`, and the navigation registry. `MemberAvatar` accepts initials and a specific palette tone; a real future participant view can replace this visual identity if requirements demand photography or an image fallback. The shell imports only small isolated client components; menu state and theme state do not hydrate the landing content.

**Page-local presentation** lives under `app/_components/`: `OverviewPreview`, `CostStory`, `HistoryPreview` and `illustrative-ledger.ts`. These encode one fictional editorial scenario, not reusable domain DTOs or API contracts. Their adjacent SCSS Modules own the visual composition, breakpoints and display treatments. `app/page.module.scss` owns only the page's actual sections, hero artwork and footer. `app/globals.scss` is limited to document canvas, text selection, focus visibility, skip-link scroll behavior and reduced motion.

## Responsive and accessibility conventions

- A maximum-width 1180px content area gives the editorial story and previews consistent alignment. At narrow widths use 16px side gutters; desktop typically has 24px.
- The top navigation becomes a Mantine menu below 850px. The wordmark, navigation and theme choice remain available at mobile width. At 550px the header promotional CTA is hidden because equivalent actions are present in main content.
- The hero and feature examples stack below approximately 830px/840px; the product preview removes its illustrative sidebar under 730px and stacks metric/cards under 520px rather than retaining desktop-width tables. The history section stacks below 830px.
- One heading hierarchy drives the real page (`h1` hero, `h2` major sections). The mock dashboard has its own nested headings, not competing real page navigation. A skip link targets `#main-content`. Actual header/footer links target `#product`, `#approach`, `#history` or the homepage.
- All interactive elements are actual Mantine controls or links with keyboard focus. A shared visible `:focus-visible` outline is defined globally. `prefers-reduced-motion: reduce` removes ornamental transitions and animated scrolling.
- Light/dark composition is driven through CSS variables instead of page-level JS toggles. The hero's citron artwork and the workspace's pine sidebar remain intentionally constant, because their foreground colors are selected together. Static screenshots and manual checks should examine both themes and responsive extremes; jsdom axe cannot establish contrast or geometry.

## Domain preview notes

The single example has four members and four equally allocated expenses: apartment €480 (Maya), groceries €88 (Jonas), dinner €164 (Leila), and train tickets €128 (Sam). Total recorded expense amount is **€860**, each person's share **€215**. After the recorded *external* settlement of €50 **Jonas → Maya**, the positions are Maya **+€215**, Jonas **−€77**, Leila **−€51**, Sam **−€87**, summing to zero. A positive amount means the participant is owed money. The dinner preview shows 4 × €41 allocations = €164. The history example includes a later apartment correction from €460 to €480, but does not promise automatic dispute resolution or payment verification. Group and expense revision identifiers are kept conceptually separate.

These exact figures are only in `app/_components/illustrative-ledger.ts`, and the financial rules remain sourced in [`docs/data-model-design.md`](../data-model-design.md). Future real workflows must use the actual domain application services, not adapt these fixtures into business logic.

## Review boundaries

This candidate favors clarity, personality and a strong marketing identity. Its serif display and immersive product stage are not necessarily suitable as universal application panel styles. The purpose of the prototype is to judge the visual language across marketing *and* plausible finance contexts, not to pre-approve precise future form layouts. There is no registration, payment processing, live finance data, backend access or analytics. The current unrelated Next.js `/api/greetings` route remains unchanged and is not part of this concept.

**Phase B gate:** compare with the other candidates and approve, iterate or reject the direction. The team must explicitly authorize any later production integration.
