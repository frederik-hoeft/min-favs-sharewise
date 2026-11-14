# Precision / Shared: ShareWise design candidate

**Status:** Phase B visual candidate, pending comparison and human selection. This document describes the prototype as implemented; it is not an approved production design system.

## Identity and intent

Precision / Shared presents ShareWise as a composed, trustworthy record for people managing shared money. The visual vocabulary combines precise typography, clean corporate surfaces, electric cobalt accents, restrained mint financial signals, and technical micro-labels. A subtle dot/grid structure and softly illuminated backgrounds introduce a futuristic character without overwhelming financial information.

The marketing page and illustrative financial workspace deliberately reuse the same design primitives. The workspace is not an implemented business feature or a proposed domain API. It is a static visual test of whether the design can serve both promotional content and dense transaction interfaces.

Principles demonstrated here:

- **Information before decoration:** large readable balances, clear payer/recipient labels and revision differences remain the focal points.
- **Precision with breathing room:** small monospace overlines and compact preview data contrast with generous section spacing.
- **One luminous accent:** cobalt identifies the brand, primary actions, selected context and section emphasis. Mint signals positive positions or preserved record status, never an implied completed in-app payment.
- **Honest interface boundaries:** illustrations are marked as sample data, and the copy explicitly states that ShareWise records payments made outside the application.

## Implementation and ownership

| Responsibility | Implementation |
| --- | --- |
| Global typography, palette, radii, spacing and standard component defaults | `src/frontend/lib/theme.ts`, `theme` via Mantine `createTheme` |
| Theme-aware semantic CSS variables | `src/frontend/lib/theme.ts`, `cssVariablesResolver` installed in `app/providers.tsx` |
| Theme initialization and light/dark/system persistence | Mantine `ColorSchemeScript`, `MantineProvider` and `useMantineColorScheme` |
| Document-level styling and focus/scroll behavior | `src/frontend/app/globals.scss` |
| Reusable product branding, public header and theme/menu controls | `src/frontend/components/` with adjacent SCSS Modules as needed |
| Page composition, static illustrative data, preview surfaces and feature artwork | `src/frontend/app/page.tsx`, `app/page.module.scss`, `app/_components/` |

Only public-site elements that plausibly recur outside this page are under `components/`. The detailed dashboard, group card, settlement illustration and history timeline remain page-local. They should not be promoted into the future finance application without re-evaluating their actual responsibilities, accessible interactions and data contracts.

## Foundations

### Color

The brand's Mantine palette is `cobalt` (10 shades; primary shade 6 in light mode and 4 in dark mode). Its important points are `cobalt[6] = #4162f5`, `cobalt[4] = #718bff`, and `cobalt[0] = #edf0ff`. `mint` is a secondary ten-shade palette, not a second primary action color; `mint[5] = #15b889`.

Semantic roles are defined once by the theme's CSS variable resolver. All page/component SCSS Modules consume these roles instead of introducing hardcoded light/dark pairs.

| Variable | Light | Dark | Use |
| --- | --- | --- | --- |
| `--sw-page` | `#f7f9fe` | `#0c1222` | Document and inner dashboard background |
| `--sw-surface` | `#ffffff` | `#151e32` | Cards, menus and workspace panels |
| `--sw-surface-2` | `#f0f4fc` | `#1c2941` | Sidebar and quiet illustration areas |
| `--sw-ink` | `#121b35` | `#f2f5ff` | High-emphasis text |
| `--sw-muted` | `#606d86` | `#a6b3cf` | Secondary explanatory copy |
| `--sw-faint` | `#64728c` | `#8090af` | Eyebrows and noncritical detail |
| `--sw-line` | `#e2e8f2` | `#2a3854` | Surface boundaries and dividers |
| `--sw-accent` | `#4162f5` | `#8da0ff` | Links, accents and selected content |
| `--sw-accent-subtle` | `#e9eeff` | `#25345d` | Selected/soft accent surfaces |
| `--sw-positive` | `#087957` | `#65e5ba` | Positive balances/status |
| `--sw-negative` | `#b64c3a` | `#ffa18f` | Negative balances (not errors) |

`--sw-hero-glow` and `--sw-card-shadow` are also scheme-dependent. Avoid treating positive/negative as success/failure: a negative participant position is an ordinary balance, not an invalid state. The intentionally dark navy history band (`#111a35`) and blue closing band (`#2b45d2`) are fixed editorial contrast surfaces in both modes, with locally specified readable text colors. Their art colors are page-specific, not general semantic tokens.

### Typography and dimensions

- **Body and headings:** system-first `Segoe UI Variable`, `Segoe UI`, `Inter` if locally installed, then system sans-serif. No remote font downloads. Heavier weights (roughly 750 to 800) and negative letter spacing characterize display headings. Default text size is Mantine's normal browser-relative scale.
- **Technical labels:** `Cascadia Code`, `SFMono-Regular`, `Consolas`, monospace; uppercase, wider tracking and modest sizes. Use for section numbering and decorative interface captions, not paragraphs or financial descriptions.
- **Radii:** Mantine `xs=5px`, `sm=9px`, `md=13px`, `lg=20px`, `xl=28px`. Standard controls default to rounded sizes; large illustrated surfaces use local radii in the same family.
- **Spacing:** Mantine tokens `xs=.5rem`, `sm=.75rem`, `md=1rem`, `lg=1.5rem`, `xl=2rem`. Custom section rhythm uses `--sw-section-space: clamp(5rem, 9vw, 9rem)` and shared page width `--sw-content-width: 1240px`.
- **Borders and depth:** quiet one-pixel `--sw-line` boundaries with limited shadows. Bright gradients and glows are restricted to marketing hero, intentional hero-metric emphasis and the closing editorial band.

### Mantine defaults

`theme.ts` centrally configures:

- `Button`: pill radius, medium default size, bold label, lightly tightened tracking.
- `ActionIcon`: pill radius, subtle variant for standard icon actions.
- `Badge`: pill radius and light default variant.
- `Paper`: large radius.
- `Menu`: large shadow.

Use existing Mantine components for buttons, badges, menus, provider and scheme state. Prefer a dedicated component variant or theme customization if later application pages need a *repeated* new control style. The cobalt hero button and editorial white closing button are intentionally local, one-off treatments; they should not redefine every standard Button.

## Components and visual continuity

`Brand` and `SiteHeader` are small reusable public-site compositions. `ColorSchemeControl` and `MobileNavigation` are narrowly scoped client components using Mantine Menu/ActionIcon and no custom persistence system. They can be reused by a future shell, although its navigation structure would need re-evaluation.

The finance preview is owned by the landing page. The `financeFixtures.ts` values model one fictional group: four equal-share expenses totaling €840, two external settlements totaling €150, and balances Maya +€120, Noah −€50, Lena −€50, Ezra −€20. The 4 × €120 accommodation shares equal the current €480 expense. Its historical revision shows €460 → €480. All totals are illustration data, not calculated by application code. No UI element submits or mutates business data.

The main page uses semantic headings and landmark elements. Preview chrome/sidebar items are deliberately noninteractive text, not fake links or buttons. The sample-data qualifier stays visible beneath the hero preview.

## Responsive and accessibility conventions

- Desktop sections use max-width containers, alternating editorial text and dense preview surfaces. The hero becomes one column below approximately 1000px; the site navigation changes to a Mantine menu below 800px; feature cards stack at narrow widths.
- The dashboard sidebar disappears below 680px; dashboard metric cards remain side-by-side until the narrowest widths, while balance/activity panels stack below 440px.
- Keep money values and revisions readable at small widths. Horizontal overflow is not an intended navigation pattern for this candidate.
- Navigation uses real anchors to sections; primary and footer navigations have labels; the first keyboard stop is a visible-on-focus skip link. Theme choices are accessible radio menu items with light/dark/system selection managed by Mantine.
- Global anchor focus outlines use the semantic accent. Other controls retain Mantine's focus handling. Smooth anchor scrolling is disabled under `prefers-reduced-motion: reduce`.
- Initial color-scheme selection uses Mantine's prehydration script to avoid an incorrect initial theme; selected values are stored using Mantine's built-in color-scheme manager. The menu defers displaying stored preference until after mount to prevent hydration discrepancies.
- Automated axe checks in jsdom do not establish visual contrast or geometry. Real-browser manual review is needed before selecting this candidate.

## Boundaries and next decision

This candidate introduces no API clients, authentication, group-management actions, financial calculations or runtime assets. The unchanged greeting route is still separate infrastructure. Full-app static export remains outside this phase; an isolated export probe is appropriate for validating the landing page without rewriting the legacy route.

Evaluate this candidate against other Phase B directions for brand distinctiveness, readability, mobile density, light/dark continuity and whether the mocked finance information feels like a plausible extension of the public site. No selection, adoption or production integration is implied by this document.
