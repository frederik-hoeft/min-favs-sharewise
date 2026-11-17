# ShareWise frontend design foundation: Quiet clarity

**Status:** Selected visual direction for the ShareWise frontend. The landing-page illustrations remain static examples, not approved workflows or final functional application screens. Implementation: `src/frontend/lib/theme.ts`, `src/frontend/components/`, and `src/frontend/app/`.

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
| `--sw-summary` | `#272a4b` | `#272a4b` | Dark financial summary surface |
| `--sw-on-inverse` | `#ffffff` | `#ffffff` | Text/icons on inverse and tinted-solid surfaces |
| `--sw-summary-muted` | `#c1c4e7` | `#c1c4e7` | Secondary text on the summary |
| `--sw-summary-border` | `#626897` | `#626897` | Subtle outline on inverse summary details |
| `--sw-inverse-muted` | `#d4d6ef` | `#d4d6ef` | Inverse panel description |
| `--sw-inverse-kicker` | `#bfc4ff` | `#bfc4ff` | Inverse panel eyebrow |
| `--sw-inverse-mark` | `#909aff` | `#909aff` | Decorative overlapping outline on inverse panel |

The custom `indigoBrand` Mantine palette is a ten-stop scale ranging from `#f2f3ff` to `#202468`. Primary shades are 6 in light mode (`#5057dc`) and 4 in dark mode (`#858af7`), improving legibility on each canvas. The primary filled `Button` foreground is mapped to `--sw-action-ink` through Mantine’s central component styles to ensure readable contrast in both modes, including the statically rendered shell. Mantine `autoContrast` also covers other filled colors. The inverse, dark-ink closing panel is the only intentionally dark surface shared by both color schemes.

Typography uses the local font stack `Inter, Segoe UI, Arial, sans-serif`; Inter is **not** downloaded, so an installed system font is used when absent. Headlines emphasize tight tracking and careful line height, not decorative font families. Mantine theme radii are `0.3125/0.5625/0.875/1.25/1.75rem` (`xs/sm/md/lg/xl`), and spacing is `0.5/0.75/1/1.5/2rem`. Component geometry and font sizes use `rem`, except for simple 1px/2px borders and relative viewport percentages. The common content width is `73.75rem` (`1180px` at the default root size), defined once as `--container-size-content`; `--sw-content-width` aliases that token. Use Mantine `<Container size="content">` for shared content alignment instead of repeating a max-width in page components.

## Components and responsibilities

Mantine owns standard controls. `lib/theme.ts` configures the primary indigo palette, rounded-pill `Button` defaults, scheme-aware primary action foreground, shared `Card` border/radius/surface treatment, `Paper` radius, and `Menu` shadow/radius. `Title`, `Text`, `Container`, `AvatarGroup`, and `Avatar` supply typography, content alignment, and participant identity primitives. Use their public theme, Styles API, and props before introducing parallel styled markup or DOM-dependent overrides. Product pages consume shared semantic roles rather than hardcoding light/dark colors. On the dark closing surface, the return link uses Mantine Button’s `white` variant with `dark` foreground rather than the low-contrast `gray` foreground; no custom button implementation or theme-specific override is needed.

`components/brand/Brand.tsx` owns the reusable wordmark. `components/participants/ParticipantAvatar.tsx` wraps Mantine `Avatar` to consistently present initials, name labels, and a small set of non-green display tones. `components/theme/AppearanceMenu.tsx` owns accessible light/dark/system selection through Mantine; it is the only page-specific interactive island. `components/navigation/ShellHeader.tsx` wraps Mantine `AppShell.Header` with the shared inset, elevated visual treatment. The public route-group layout (`app/(public)/layout.tsx`) owns the AppShell configuration and its public navigation/footer; future authenticated routes can supply separate AppShell navigation and optional sidebars without changing the shared header surface. The root `Providers` installs the common theme, while the public page remains a Server Component. Navigation actions use Mantine `Button` and its `rightSection` slot with a small shared vector-arrow component; no external icon package or Unicode control glyphs are required.

The product illustrations are **page-owned compositions** in `app/(public)/_components/`: `OverviewPreview` owns the hero illustration; `DetailsPreview` arranges `ExpenseLedgerPreview`, `SettlementPreview`, and `HistoryPreview`. Each component has its own SCSS Module. The Sass partial `_preview-panel.scss` shares only card-specific heading, kicker, description, and spacing mixins; it does not publish global selectors or prescribe production finance layouts. `preview-data.ts` owns the fictional group/ledger fixture and stable per-person display tones. It is neither an API DTO nor a financial calculation service. Extract future business components only when real application features establish reusable responsibilities.

`app/(public)/page.module.scss` owns landing-page geometry, local grids, and section rhythm. `app/globals.scss` contains only document canvas, selection, shared link focus, and reduced-motion scroll behavior. `styles/_breakpoints.scss` owns named Sass media-query thresholds. SCSS Modules import that partial with `@use` and call `@include breakpoints.down(name)` instead of embedding numeric media queries. CSS Modules never reach into Mantine implementation selectors; `ParticipantAvatar` uses Mantine’s documented `classNames.placeholder` Styles API.

Frontend component and accessibility tests live in the dedicated `src/frontend/tests/` project with its own `package.json`, TypeScript project, Vitest configuration, and setup. The parent frontend npm install supplies the locked test dependencies; the Next.js production TypeScript project excludes `tests/`. From `src/frontend/`, run `npm run test` and `npm run test:typecheck`, or invoke `npm run test` inside `tests/`. Do not place tests under `app/` or `components/`.

## Responsive and accessibility conventions

- Content uses a `73.75rem` cap with at least `1.25–1.5rem` horizontal gutters. The hero and product bento collapse below the named `tablet` (`53.125rem`) and `details` (`55.625rem`) thresholds; the three steps stack at `mobile` (`38.125rem`). Finance rows preserve amount alignment at narrow widths.
- The floating AppShell header stays fixed on scroll and has a bordered, elevated surface in both color schemes. Public navigation destinations are on-page anchors with scroll clearance for the fixed header; skip link, semantic `header/nav/main/section/footer`, and a unique heading hierarchy are present. There are no disabled or false-signaling business buttons.
- Links have a visible `:focus-visible` outline. Mantine supplies focus and keyboard operation for the appearance selector. The layout respects `prefers-reduced-motion` by disabling smooth scrolling and decorative rotations.
- Amounts use tabular numerals. Positive positions are labeled as positive and have a `+` sign; negative ones have a `−` sign. Neither meaning relies on color alone. Dark-mode contrast and real viewport geometry require browser inspection beyond jsdom axe.

## Domain consistency of the sample

Four participants share €680 in three equally allocated expenses: apartment €480 (Maya), dinner €120 (Leo), museum tickets €80 (Ana). Each has €170 of expense shares. Noah's **external** €60 payment to Maya, recorded as a settlement, results in positions Maya +€250, Leo −€50, Ana −€90, Noah −€110. The balances sum to zero. Dinner was originally recorded at €100 and corrected to €120 as a new revision. A separate group-local revision sequence records that correction, later settlement and museum expense; the visual does not imply that ShareWise executes or verifies bank payments.

## Scope and limitations

The mock finance cards contain no state, network calls, financial algorithms, authentication or implemented workflows. The provided Next.js server-side greeting infrastructure is not modified. A disposable static-export probe can exclude that unrelated route; a full application export remains a separate deployment task. Functional group, expense, and finance views will be designed and implemented separately under their respective task specifications.
