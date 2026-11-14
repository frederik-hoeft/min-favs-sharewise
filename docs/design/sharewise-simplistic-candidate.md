# ShareWise design candidate: Quiet clarity

**Status:** Phase B design candidate for team comparison. This is not an approved product design system. Implementation: `src/frontend/app/page.tsx`, `src/frontend/lib/theme.ts`.

## Intent and visual identity

The visual direction is **quiet clarity**: unembellished, structured group finances with approachable typography and enough space around financial information to make it easy to scan. The public page and illustrative application views share the same surfaces, typography, radius scale and indigo accent. The concept deliberately avoids green, teal and olive. There are no stock photos, decorative gradients behind text, remote fonts or external runtime assets. A single concentric-square symbol conveys overlapping participants and shared context.

Large editorial headlines and generous whitespace introduce the product, followed by a realistic, calmer-density financial UI. Only one example group is used throughout. The illustrations show group membership, actual expense allocations, external settlement recording, group balances and versioned history. They are not interactive previews of implemented functionality.

## Design tokens and themes

`lib/theme.ts` owns all shared values through Mantine `createTheme` and `cssVariablesResolver`. `app/providers.tsx` installs both. Mantine's color-scheme script and provider default to `auto` (OS preference), and the accessible appearance menu can explicitly choose light/dark/system. Mantine's storage manager persists selection.

| Role | Light | Dark | Purpose |
| --- | --- | --- | --- |
| `--sw-page` | `#f8f8fa` | `#11131c` | Overall page canvas |
| `--sw-surface` | `#ffffff` | `#1d2030` | Primary cards/panels |
| `--sw-surface-subtle` | `#f2f3f7` | `#262a3a` | Secondary rows, subdued areas |
| `--sw-surface-accent` | `#eeefff` | `#292c53` | Tint for accent-adjacent content |
| `--sw-ink` | `#191b2b` | `#f2f2fb` | Main body/headline text |
| `--sw-muted` | `#60657a` | `#aeb3c9` | Explanations and metadata |
| `--sw-border` | `#e3e5ed` | `#35394e` | Gentle separation |
| `--sw-shadow` | `rgba(27,31,65,.085)` | `rgba(0,0,0,.26)` | Elevated preview emphasis |
| `--sw-hero-glow` | `#e6e8ff` | `#22284c` | Decorative atmosphere |
| `--sw-inverse` | `#1b1e33` | `#252945` | Consistent inverse closing surface |
| `--sw-action-ink` | `#ffffff` | `#17192b` | Accessible foreground for primary filled actions |
| `--sw-accent` | Mantine primary filled | Mantine primary filled | Shared indigo emphasis |

The custom `indigoBrand` Mantine palette is a ten-stop scale ranging from `#f2f3ff` to `#202468`. Primary shades are 6 in light mode (`#5057dc`) and 4 in dark mode (`#858af7`), improving legibility on each canvas. The primary filled `Button` foreground is mapped to `--sw-action-ink` through Mantine’s central component styles to ensure readable contrast in both modes, including the statically rendered shell. Mantine `autoContrast` also covers other filled colors. The inverse, dark-ink closing panel is the only intentionally dark surface shared by both color schemes.

Typography uses the local font stack `Inter, Segoe UI, Arial, sans-serif`; Inter is **not** downloaded, so the installed system font is used when absent. Headlines emphasize tight tracking and careful line height, not decorative font families. Theme radii are `5/9/14/20/28px` (`xs/sm/md/lg/xl`) and spacing `8/12/16/24/32px`. The content width is `1180px` via `--sw-content-width`.

## Components and responsibilities

Mantine owns standard controls. The global theme supplies the primary indigo palette, rounded-pill `Button` default, scheme-aware primary action foreground and consistent `Menu` shadow/radius. Do not recreate buttons, dropdowns or color-scheme storage in page-local components. Product pages should consume shared color roles, rather than hardcoding their own light/dark palettes.

`components/brand/Brand.tsx` is the reusable wordmark, with component-local SCSS. `components/theme/AppearanceMenu.tsx` is the only client island needed for landing-page behavior; its selection delegates to Mantine. The root `Providers` remains the shared client boundary. The public `page.tsx` stays a server component.

The product illustrations (`app/_components/OverviewPreview` and `DetailsPreview`) are **page-owned compositions** with adjacent SCSS Modules. They should not become production group-management or finance components by accident. `preview-data.ts` owns the static fictional group/ledger example; it is deliberately not an API DTO or financial calculation service. Future application components should be extracted only from demonstrated, repeated business UI patterns, not from these one-off artwork arrangements.

`app/page.module.scss` owns landing-page geometry, local grids and section rhythm. `app/globals.scss` contains only page canvas, selection, shared link focus and reduced-motion scroll behavior. Shared roles live in the theme, not globals; no brittle Mantine DOM selector overrides are necessary.

## Responsive and accessibility conventions

- Content is capped at `1180px` with at least `20–24px` horizontal margins. The hero and product bento collapse to single-column layouts below ~`850–890px`, and the three steps stack below `610px`. Finance rows preserve amount alignment at narrow widths.
- All navigation destinations are on-page anchors; skip link, semantic `header/nav/main/section/footer`, and a unique heading hierarchy are present. There are no disabled or false-signaling business buttons.
- Links have a visible `:focus-visible` outline. Mantine supplies focus and keyboard operation for the appearance selector. The layout respects `prefers-reduced-motion` by disabling smooth scrolling and decorative rotations.
- Amounts use tabular numerals. Positive positions are labeled as positive and have a `+` sign; negative ones have a `−` sign. Neither meaning relies on color alone. Dark-mode contrast and real viewport geometry require browser inspection beyond jsdom axe.

## Domain consistency of the sample

Four participants share €680 in three equally allocated expenses: apartment €480 (Maya), dinner €120 (Leo), museum tickets €80 (Ana). Each has €170 of expense shares. Noah's **external** €60 payment to Maya, recorded as a settlement, results in positions Maya +€250, Leo −€50, Ana −€90, Noah −€110. The balances sum to zero. Dinner was originally recorded at €100 and corrected to €120 as a new revision. A separate group-local revision sequence records that correction, later settlement and museum expense; the visual does not imply that ShareWise executes or verifies bank payments.

## Limits of this candidate

The mock finance cards contain no state, network calls, financial algorithms, authentication or implemented workflows. The provided Next.js server-side greeting infrastructure is not modified. A disposable static-export probe can exclude that unrelated route; a full application export remains a separate deployment task. Design selection, final application navigation and production integration remain subject to the Phase B human review gate.
