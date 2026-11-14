# ShareWise corporate design candidate (P2.0e Phase B)

**Status:** Independent candidate for human comparison, **not** an approved product design. This document describes the visual conventions actually implemented by the [candidate landing page](../../src/frontend/app/page.tsx). Nothing here commits future finance UI layouts or the final brand identity.

## Direction

**Clear by design.** ShareWise uses a contemporary, restrained financial-product visual language: ink-navy headings, cobalt-blue action surfaces, teal accounting accents, generous white space, fine borders, modest shadows and precise numerical typography. It should feel dependable without resembling a banking app that handles money. Marketing and product-interface previews deliberately share the same background, text, border and status roles.

The landing page uses editorial-scale headings and a single layered application preview, followed by an expense allocation and an external-settlement record in one composition. The dark financial-history section adds hierarchy without changing the underlying design language. Rounded geometry is moderate, not playful; visual flourishes are limited to a light grid, an outline halo and one CTA ornament. All illustration is local HTML/CSS/SVG with system fonts, not remote imagery.

The logo is a simple connected three-node mark next to a lower-case wordmark. It is a candidate asset in [`BrandMark`](../../src/frontend/components/brand-mark.tsx), not a production identity commitment.

## Tokens and theme

`src/frontend/lib/theme.ts` is the source of truth. `MantineProvider` in `app/providers.tsx` installs both the `theme` and `cssVariablesResolver`; the root layout installs Mantine CSS and `ColorSchemeScript` using `defaultColorScheme="auto"` before hydration.

### Palette and semantic roles

| Role | Light | Dark | Usage |
| --- | --- | --- | --- |
| `--sw-canvas` | `#f8fafc` | `#0c1727` | Document background and section continuity |
| `--sw-surface` | `#ffffff` | `#132338` | Standard cards and content surfaces |
| `--sw-surface-raised` | `#ffffff` | `#1a2d43` | Elevated workspace preview |
| `--sw-subtle` | `#f0f5f8` | `#1a2b40` | Chrome, rows and nested panels |
| `--sw-ink` | `#162b41` | `#edf5fc` | Primary text |
| `--sw-muted` | `#5c7083` | `#acbdcd` | Supporting text and labels |
| `--sw-line` | `#dae5ed` | `#304359` | Borders and separators |
| `--sw-accent` / `--sw-credit` | `#066f68` | `#6bd4c8` | Non-action emphasis, positive balances |
| `--sw-accent-soft` | `#e1f5f0` | `#173f47` | Teal soft fill |
| `--sw-debit` | `#ac5663` | `#ffacb4` | Negative balances |
| `--sw-dark-panel` | `#142b43` | `#1b344d` | Financial-history section |

The named Mantine color scales `brand` (cobalt), `ocean` (teal) and `slate` (neutral) each have ten shades. `brand` is the theme's primary color, with filled controls using shade 6 in light mode and shade 6 in dark mode. Use Mantine `color="brand"`, `color="ocean"` or normal component color props for standard UI, not hardcoded page-specific control colors. Reserve positive/negative financial sign coloring for credit/debit semantics; never convey those meanings by color alone.

`--sw-hero-glow`, `--sw-grid` and `--sw-panel-shadow` are color-scheme-sensitive background/elevation treatments, not new component APIs. `--sw-content-width` is a `1200px` design reference, while the actual responsive shell uses Mantine `<Container size="xl">`. The dedicated navy workspace-sidebar fills and CTA blue are deliberate, one-off compositions, not global tokens for all future sidebars.

### Typography, spacing and shape

- System font stack: `Segoe UI`, `Helvetica Neue`, Arial, sans-serif. No downloads or Next font loader. Bold, tight-letterspaced display headlines are page-local, with normal text following Mantine defaults.
- Mantine spacing steps: `xs=0.5rem`, `sm=0.75rem`, `md=1rem`, `lg=1.5rem`, `xl=2rem`. Section padding is responsive and page-local. Typical body copy uses `0.8–1.1rem` with generous line height; tiny sizes belong only to reduced-scale **illustrative** dashboards.
- Mantine radii: `xs=0.375rem`, `sm=0.5rem`, `md=0.75rem`, `lg=1rem`, `xl=1.5rem`. Normal controls use `md`, preview panels `lg`/`xl` or adjacent matching CSS radii.
- Financial amounts use tabular alignment where columns need it; the mock workspace uses bold positive/negative totals, and no ambiguous unlabelled signs.

### Standard control defaults

Theme-level `Button.extend` sets `radius="md"` and weight `650` with restrained tracking; `ActionIcon.extend` uses `md` radius; `Badge.extend` uses `xl`; `Card.extend` and `Paper.extend` use `lg`. The primary action uses Mantine's filled `brand` scheme. A theme-specific primitive is customized only once here; do not copy its styles into page SCSS or introduce a second control library. Some intentionally inverted CTAs choose the existing Mantine white variant locally for contrast.

## Component ownership

| Layer | Implementation and intent |
| --- | --- |
| Theme | [`lib/theme.ts`](../../src/frontend/lib/theme.ts): palette, semantic roles, component defaults, spacing/radii and typography |
| Truly global styles | [`app/globals.scss`](../../src/frontend/app/globals.scss): page background, anchor scrolling, selection, focus indication and reduced-motion behavior only |
| Shared UI | [`components/brand-mark.tsx`](../../src/frontend/components/brand-mark.tsx) and [`components/color-scheme-menu.tsx`](../../src/frontend/components/color-scheme-menu.tsx): brand lockup and Mantine light/dark/system preference menu, suitable for other shells |
| Landing-only | [`app/page.tsx`](../../src/frontend/app/page.tsx), `app/page.module.scss`, and [`app/_components/`](../../src/frontend/app/_components/): public header/navigation and expressive marketing/dashboard compositions. They are not the feature-page architecture. |
| Illustrative data | [`app/_data/preview.ts`](../../src/frontend/app/_data/preview.ts): static group, allocation, settlement and history fixture. [`app/_data/navigation.ts`](../../src/frontend/app/_data/navigation.ts) is shared between server-rendered header and client drawer. |

`page.tsx`, `WorkspacePreview`, `ExpensePreview`, `SettlementPreview` and `HistoryPreview` are server-rendered presentation components. Client JavaScript is limited to the root Mantine provider, the appearance menu, and the mobile navigation drawer. Mock dashboard navigation and financial rows are not operational controls, and no route/API client/backend is used by the landing page.

## Responsive and interaction conventions

The public header uses a semantic navigation region at desktop widths and a Mantine `Burger`/`Drawer` below `830px`. The header CTA disappears below `510px`; all target anchors remain available in the drawer. The hero becomes one column below `900px`; the mock workspace removes its decorative sidebar below `550px` while retaining readable financial data. The two financial previews stack below `730px`. Headings scale through `clamp()` and layout uses the space of each composition rather than fixed-height mock screens.

The page has a skip link, one main landmark, real hash-based navigation destinations, visible `:focus-visible` outlines, alt-free decorative SVGs marked as hidden, and motion-reduced smooth scrolling. Mantine's `ColorSchemeScript` and provider share `auto` as the default; explicit choices use Mantine's built-in persisted color scheme. The menu labels its current value for assistive technologies. Components don't infer a theme from transient local React state or load content externally. The static examples intentionally present no interactive finance buttons.

Viewport/layout and real color contrast must be checked in a browser, since jsdom/axe cannot measure either correctly. Recommended comparison captures are desktop 1440×900 and mobile 390×844 in light/dark, including full-page captures. Full-app export remains blocked by the unrelated server-only `/api/greetings` route; an isolated probe must remove that route **only in the probe** and prove `/` independently exportable.

## Illustrative finance contract

The group fixture is intentionally small enough to audit: a €480 rental paid by Ava, a €120 grocery expense paid by Ben, and four participants sharing each expense equally. Clara has externally sent Ava €60, which is **recorded**, not processed. Present balances are Ava **+€270**, Ben **−€30**, Clara **−€90**, Daniel **−€150**, summing to zero. Both allocation totals equal their respective expense totals. Revisions 9–12 include the original €440 rental, a €120 grocery expense, the external settlement, and a corrected €480 rental in that order. The preview illustrates the domain's revision semantics but does not implement calculation, revision writes, or history navigation.

## Review status

This is **one** candidate at the Phase B human comparison gate. Its design and component defaults are intentionally not merged into the accepted foundation, and this document is not an accepted design-system standard. Production integration, actual application journeys and static Nginx deployment are outside this patch.
