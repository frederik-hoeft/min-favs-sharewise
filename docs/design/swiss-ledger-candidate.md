# ShareWise: Swiss Ledger (Phase B design candidate)

**Status:** Independent, unapproved P2.0e Phase B prototype. For team comparison only, not the selected ShareWise design system or a specification for production financial screens.

## Direction

**Swiss Ledger** is a typography-first interpretation of a shared financial record. It borrows the grid, restrained labeling, editorial hierarchy and deliberate negative space of Swiss information design without becoming a retro poster. The site and its illustrative application screens share the same ruled surfaces, numerical alignment and understated wine accent. No charts or decorative fintech graphics are needed to establish credibility: the *ledger itself* is the central image.

Principles demonstrated in the implementation:

- Lead with readable typography and a clear proposition. Supporting product detail is arranged in consistent vertical and horizontal rules rather than stacks of shadowed cards.
- Use **oxblood once, for emphasis and orientation**: active navigation, brand punctuation, positive balances, key amounts and important headings. Do not use it to communicate a universal financial success/failure state.
- Preserve neutral space around financial information. Numeric columns use tabular figures, and positive/negative balances use an explicit `+` or `−`, not color alone.
- Present the actual business distinction: settlements are **records of external transfers**, not in-product payments; revisions preserve history instead of replacing it.

The candidate is implemented on `src/frontend/app/page.tsx`. The landing page's finance views are illustrative and static; no authentication, API client, payment handling or financial calculation is implemented.

## Foundation and semantic tokens

The foundation is in [`src/frontend/lib/theme.ts`](../../src/frontend/lib/theme.ts) and applied by [`src/frontend/app/providers.tsx`](../../src/frontend/app/providers.tsx). `createTheme` supplies the Mantine color collection, standard component defaults, typography, spacing and radii. `cssVariablesResolver` supplies the application-specific semantic variables. Custom components consume these roles in their adjacent SCSS Modules instead of repeating hex values.

| Role | Token | Light | Dark | Intended use |
| --- | --- | --- | --- | --- |
| Page background | `--sw-canvas` | `#f7f6f3` | `#191817` | Main editorial field |
| Primary surface | `--sw-paper` | `#fffefa` | `#22211f` | Records and application surfaces |
| Primary text | `--sw-ink` | `#252322` | `#f0ede8` | Headings, data, body text |
| Secondary text | `--sw-muted` | `#69645e` | `#b6afa6` | Labels, contextual details |
| Dividers | `--sw-line` | `#d9d5ce` | `#48443f` | Hairline separation |
| Subtle background | `--sw-soft` | `#efede8` | `#2c2a27` | Sidebar and alternate section |
| Editorial accent | `--sw-accent` | `#702d39` | `#db9ca6` | Brand and emphasis |
| Accent wash | `--sw-accent-quiet` | `#f3e8e8` | `#3a292e` | Selection or quiet emphasis |
| Feature background | `--sw-inverse` | `#262224` | `#2c2528` | Financial-history editorial section |
| Feature text | `--sw-inverse-ink` | `#faf7f2` | `#faf7f2` | High contrast on feature background |
| Feature accent | `--sw-inverse-accent` | `#d69aa5` | `#d69aa5` | Heading emphasis on feature background |
| Feature secondary | `--sw-inverse-muted` | `#c3b8b6` | `#cbbfc0` | Context on feature background |
| Feature rules | `--sw-inverse-line` | `#5a5051` | `#62565a` | Ruled history rows |

The feature region deliberately remains dark in both color schemes to create a consistent editorial focal point. Dark mode otherwise adapts all application and marketing surfaces. Mantine's `wine` shades (`0` through `9`) are the only custom accent family; the warm `gray` ramp replaces Mantine's default neutral family. The primary shade is `wine.7` in light mode and `wine.5` in dark mode. Avoid associating semantic debts with Mantine's generic green/red status colors: financial positions are **signed amounts**, not success/error notifications.

| Foundation setting | Established value |
| --- | --- |
| Font stack | Arial, Helvetica Neue, Helvetica, sans-serif; no remote font dependency |
| Heading weight | 600 with editorial negative letter spacing applied only in the page styles |
| Body text | Mantine-default text sizing, with local text hierarchy where appropriate |
| Spacing scale | `xs=.5rem`, `sm=.75rem`, `md=1rem`, `lg=1.5rem`, `xl=2rem` |
| Corner radius | `xs=.25rem`, `sm=.375rem`, `md=.5rem`, `lg=.75rem`, `xl=1rem`; default `xs` |
| Main max width | `--sw-max-content: 1240px` plus gutters |
| Horizontal padding | `--sw-page-gutter: clamp(1.25rem, 4.5vw, 5.5rem)` |
| Section rhythm | `--sw-section-space: clamp(5rem, 9vw, 10rem)` |
| Elevation | A single soft shadow on the large illustrative application frame; most surfaces use rules instead of elevation |

### Standard Mantine components

Mantine is the source of truth for standard controls. The theme centrally specifies:

- `Button`: `xs` radius, weight 600, slightly tight tracking. Variant/color remain Mantine's supported API: the hero uses a filled primary button; the outro uses an outline button.
- `Badge`: `xs` radius, light default variant and measured label tracking. Individual outlined labels use Mantine's supported `variant` prop.
- `Table` and `SegmentedControl`: use the standard Mantine implementations and keyboard/ARIA behavior, without global DOM-selector overrides. The group-balance table adds only domain-specific data typography in its own module.

Do not build an alternative button, menu, input or table component in application code. New global control customizations belong in `theme.ts`, not in a page stylesheet.

## Ownership and composition

- **Theme and global baseline:** `src/frontend/lib/theme.ts`; provider initialization stays in `app/providers.tsx`. `app/globals.scss` contains only document-wide background, selection, focus visibility, and reduced-motion behavior.
- **Reusable application elements:** `components/site-header` handles public navigation; `components/theme-control` owns the only interactive client state; `components/brand` defines the compact brand mark; `components/finance/group-balance-table` is a reusable read-only signed-position presentation. `lib/format-money.ts` formats integer minor units in the supplied currency, not accounting calculations.
- **Page compositions:** `app/page.tsx` owns the landing-page narrative. `app/page.module.scss` owns the large-scale grids, rhythm, section treatments and breakpoint behavior. `app/_components` owns the sample group's fixed content and the one-off overview, allocation and history previews. They are not business feature components and should not be mistaken for future functional screens.

Example: future production balance tables should consume `--sw-paper`, `--sw-line`, `--sw-muted` and `--sw-accent`, use Mantine's table APIs, and ensure all amounts have signs and tabular figures. They should **not** import the illustrative fixture or page-specific history layout.

## Responsive layout and accessibility

The page is designed for a maximum 1240px content grid and contracts to fluid gutters. At `1080px`, the opening headline and introduction stack. At `940px`, the public header navigation wraps to a second row **instead of disappearing**. At `800px`, the two-column editorial sections stack and the single-group financial sample uses available width. The preview's illustrative sidebar disappears at `780px`; the finance content is still visible and legible. At `580px`, summary measures and balance/activity areas stack, and at `550px` the core type hierarchy scales down. The smallest intended viewport width is 320px.

- The document uses one `h1`, section headings, a main landmark, real navigation links, semantic figures/captions, a table with headers, and an ordered history list.
- Skip-to-content and anchor navigation are keyboard usable. Mantine's native segmented radio interaction selects `Auto` (system), `Light` or `Dark` and persists preference using its built-in color-scheme manager.
- `ColorSchemeScript` sets initial system preference without a visible flash; no custom `localStorage` or media query state is added.
- Links have an explicit focus outline, standard Mantine controls retain built-in keyboard focus, and `prefers-reduced-motion` disables smooth anchor scrolling.
- Business-style rows and preview navigation labels are **presentational text**, not dead buttons or false links. Copy and captions disclose illustrative amounts and the non-payment nature of settlement entries.
- Unit/component accessibility checks cannot establish actual layout, contrast or responsive overflow. Those require visual/manual browser verification before a design is selected.

## Fixture integrity and limits

The illustrative Coast House uses four actors and a single accounting currency, EUR. Three current expenses total €1,400.00: cabin rental €840, groceries €240, and car rental €320. Each is split equally across four actors, and the payout owners are Clara, Noah and Noah respectively. One **external settlement** of €100 from Mina to Clara is recorded. The displayed net positions are Clara +€390, Noah +€210, Mina −€250 and Leo −€350. They sum to zero and reflect the domain's recorded settlement sign convention.

Group revision `005` follows three expense creations, a correction of groceries from €220 to €240 (record revision 2), and the external settlement. The older €220 version remains represented in history, while current positions reflect €240. These hand-curated figures are not calculated by the UI and establish no API or persistence contract.

The design deliberately does **not** establish a functional sidebar, forms, error alerts, API loading states, interactive histories, financial calculation policies or a production app shell. Those require design integration and explicit decisions after Phase B comparison.
