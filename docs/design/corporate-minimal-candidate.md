# ShareWise corporate-minimal visual candidate

**Status:** P2.0e Phase B design candidate, pending team comparison and human selection. These conventions describe the implemented prototype, not an approved production design system.

## Direction

**Quiet structure, clear financial meaning.** ShareWise uses neutral editorial typography, restrained teal accents, flat bordered surfaces, and deliberate whitespace. The public page and mock application panels share the same visual vocabulary: calm cards, finely separated records, compact labels, and prominent tabular monetary values. The tone is approachable but factual, avoiding decorative gradients, noisy illustrations, and payment-app imagery. Green/teal distinguishes positive positions and the brand; a warm muted rust identifies negative balances. Status never depends on color alone.

The landing page introduces ShareWise as a **shared financial record**, not a payment processor. Its product previews demonstrate a group and its participants, exact stored expense shares, balances, externally recorded settlements, historical revisions, and an earlier balance snapshot. These panels are examples, not production screen specifications or interactive workflows.

## Tokens and theme ownership

The central source of truth is [`src/frontend/lib/theme.ts`](../../src/frontend/lib/theme.ts): Mantine's `createTheme` sets the brand color scale, primary shade, font family, radii, spacing, and standard component defaults. A `cssVariablesResolver` provides the additional semantic roles consumed by SCSS Modules; the role names do not depend on the current page. `app/providers.tsx` installs the theme once for the application.

### Semantic roles

| CSS variable | Light | Dark | Usage |
| --- | --- | --- | --- |
| `--sw-canvas` | `#f8f9f7` | `#111a1c` | Page background |
| `--sw-surface` | `#ffffff` | `#1b272a` | Header, cards, app panels |
| `--sw-surface-muted` | `#f2f5f3` | `#223034` | Subtle inset areas |
| `--sw-surface-accent` | `#eaf5f1` | `#1b3935` | Gentle highlighted areas |
| `--sw-text` | `#202d30` | `#f0f4f2` | Main text, currency |
| `--sw-text-muted` | `#5d6b6e` | `#aabbb9` | Supporting text |
| `--sw-line` | `#dce4e1` | `#354548` | Dividers and normal borders |
| `--sw-line-strong` | `#c8d4d0` | `#4a5e5e` | Prominent borders |
| `--sw-brand-text` | `#0d6458` | `#86dfcd` | Section labels, links, accent text |
| `--sw-on-brand` | `#ffffff` | `#092c25` | Text on filled brand controls; contrast-adjusted per scheme |
| `--sw-positive` | `#116f60` | `#78d8c4` | Positive financial positions |
| `--sw-negative` | `#9b543c` | `#f0ac91` | Negative financial positions |
| `--sw-neutral` | `#657476` | `#abb9b7` | Zero balances |

The Mantine **`brand` 10-step palette** runs from `#effbf8` (0) to `#0b413b` (9), with primary shade 7 (`#106d61`) in light mode and 5 (`#3bb29e`) in dark mode. Use Mantine's `brand` for controls and its design-token variables for typography, spacing, and corner radii. Use semantic `--sw-*` roles for meaningful backgrounds, dividers, and financial statuses. Do not embed brand or financial role hex codes in page-local styling.

### Type and geometry

- Fonts use an **offline, OS-native system sans stack** with Inter first *only if already installed locally*. No Google Font, font download, or font asset is required. Headings use heavier weights (~700), tight tracking, and uncluttered sentence case. Financial values use `font-variant-numeric: tabular-nums` so columns remain stable.
- Mantine spacing steps: **8 / 12 / 16 / 24 / 32 px** for `xs` through `xl`. Layout-scale section separation is composed locally using responsive `clamp(...)` rather than a second arbitrary global spacing system.
- Mantine radii: **4 / 6 / 9 / 13 / 18 px** for `xs` through `xl`. Mock workspace panels use a slightly larger outer radius (15 px) for visual containment, not a reusable control radius.
- Maximum intended content width: **1200 px** (`--sw-content-width`, matching the page's Mantine `Container size="var(--sw-content-width)"` usage). The marketing hero is a generous two-column split at desktop widths; preview cards stack on narrow screens.
- Surfaces are primarily **flat** and outlined. The large hero workspace uses one low-opacity shadow to distinguish the illustrative app surface; ordinary cards do not stack decorative shadows.

### Standard Mantine components

Global component changes live in `lib/theme.ts` rather than repeated per-page overrides:

- `Button`: 9 px medium radius, semi-bold label and Mantine `autoContrast` for filled button text; use the native `filled` brand and `default` variants for calls to action and secondary navigation. A global `variantColorResolver` retains Mantine defaults except for filled brand controls, whose text uses `--sw-on-brand` to preserve contrast on the brighter dark-mode teal.
- `Card`: border enabled, `lg` radius, and application surface/border semantic roles by default. Preview-specific interior layout belongs in local SCSS, not a global Card rewrite.
- `Badge`: small radius and light default variant. Status chips remain text-labeled.
- `SegmentedControl`: compact (`xs`) default size and medium radius. The header's Light/System/Dark selector has local sizing/surface styles, but its selection, keyboard behavior, and control structure are Mantine-owned.

Do not re-create these controls in custom HTML. Additional global changes should be justified by repeated use across actual application features rather than anticipated screens.

## Implementation boundaries

| Owner | Responsibility |
| --- | --- |
| `lib/theme.ts`, `app/providers.tsx` | Candidate-wide design tokens, Mantine configuration, color-scheme provider |
| `app/globals.scss` | Document background, smooth anchors, focus outline, reduced motion; no page component selectors |
| `components/brand/Brand.*` | Reusable visual identity for app shell, site header and footer |
| `components/theme-selector/ThemeSelector.*` | Reusable explicit color-scheme selection using Mantine's manager |
| `components/balance-value/BalanceValue.*` | Reusable semantic presentation of signed balance text; **not** money arithmetic |
| `app/page.tsx`, `app/page.module.scss` | Marketing narrative, anchor navigation, layout and responsive page composition |
| `app/_components/*Preview.*`, `app/_components/preview-data.ts` | One-off, static illustrative group/finance interfaces and reconciled example content |

The page and its preview panels remain **Server Components**; the only client behavior beyond Mantine's root provider is the theme selector. There is no API client, authentication dependency, finance calculation, dynamic import, remote asset, or backend-state requirement. No mock preview element suggests an operational business action. The landing page's actual links are same-document anchors and Mantine anchor Buttons.

## Color scheme, responsiveness and accessibility

`ColorSchemeScript` in `app/layout.tsx` establishes the chosen or system scheme before paint. `MantineProvider` defaults to `auto`, and its built-in localStorage color-scheme manager retains explicit Light, System or Dark choice and tolerates unavailable writes. The theme selector defers displaying the persisted selection until mounted so its server and hydration output agree. In a fresh browser without a stored value, the OS's preference determines the appearance. Colors switch through semantic variables, not JavaScript branches within the mock previews.

- At **900 px**, the hero becomes vertically stacked. At **700 px**, the header gains a second line for the same accessible anchor navigation, the two detail panels stack, and benefits become a vertical list. Smaller viewports reduce gutters and simplify purely decorative secondary labels. No essential information is removed.
- A keyboard-first skip link targets the main content. The site uses a single `h1`, section-level `h2`s, lower-level headings within actual sections, semantic `header`, `nav`, `main`, `section`, `footer`, and `figure` with a visible sample-data caption. The theme selector is a labeled radio group. Currency sign and textual labels identify balances independently of color.
- Keyboard focus receives a visible global outline. Reduced-motion preference disables smooth scrolling and nav-link transitions. Test with keyboard, zoom, both color schemes, 320–390 px and desktop widths. jsdom/axe can check structure, but a browser is needed for contrast, focus, geometry and responsive layout.

## Example-ledger semantics

`app/_components/preview-data.ts` documents the static reconciliation. Three equal-split expenses total **€240**: groceries €96 paid by Alex, cabin supplies €54 paid by Morgan (corrected from €48), and dinner €90 paid by Sam. After Morgan records an external €10 transfer to Sam, the current group balances are **Alex +€16, Morgan −€16, Sam €0**. Their sum is zero. At **group revision 03**, before the correction and external settlement, historical positions were **Alex +€18, Morgan −€30, Sam +€12**, also totaling zero. The €54 cabin allocation remains three equal €18 shares. Revision 04 corrects that expense; revision 05 records the external settlement. This fixture demonstrates published domain semantics without implementing a finance algorithm.

## Candidate trade-offs

This direction prioritizes legibility, trust, and continuity between the public website and finance UI over expressively illustrated marketing. It is intentionally conservative: the hero relies on a credible illustrative workspace rather than photographs or animations. The prototype does not establish branding for user-generated assets, production navigation, onboarding, group workflows, or full data-density/empty/error states. The team should judge its visual qualities against other candidates at the **Phase B human gate**, before promoting any conventions to an approved design system.
