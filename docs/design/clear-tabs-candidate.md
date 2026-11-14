# Clear Tabs: Phase B design candidate

**Status:** Independent P2.0e Phase B proposal for comparison and human review. Not an approved ShareWise design system or a specification for future business screens.

## Direction

**Good times. Clear tabs.** is a warm, editorial identity for a trustworthy shared-finance product. The public site uses generous parchment space, large literary-style headlines and a bright chartreuse accent. The illustrative application uses the same green family, typography, rounded surfaces and status treatments in a denser information hierarchy. The contrast is deliberate: the public-facing page feels inviting while financial records remain legible and structured.

The identity is intentionally built from CSS and locally available system fonts. No external imagery, icon service, font CDN or runtime data is necessary. Rounded geometry and a four-tile ShareWise mark provide a recognizable visual signature without a separate icon dependency. The type-heavy design may have minor metric differences between operating systems because it does not bundle a proprietary font.

The page tells one product story, not five isolated future screens: a four-person weekend group, its recorded expenses and allocations, an external settlement, current positions and immutable history. All financial detail is illustrative. The real business interfaces will be designed and implemented separately.

## Shared foundation

The implementation source of truth is [`src/frontend/lib/theme.ts`](../../src/frontend/lib/theme.ts). `Providers` installs the Mantine theme and semantic CSS variables; `ColorSchemeScript` in the root layout and Mantine's color-scheme manager coordinate initial rendering and saved **System / Light / Dark** preferences.

### Colors and semantic roles

| Role / variable | Light | Dark | Use |
| --- | --- | --- | --- |
| `--sw-canvas` | `#faf8f0` | `#111c19` | Document canvas |
| `--sw-canvas-subtle` | `#f0f1e8` | `#1a2924` | Alternating editorial section |
| `--sw-surface` | `#ffffff` | `#1e2d27` | App cards, record panels |
| `--sw-surface-tint` | `#eff4eb` | `#273d33` | Quiet panels, chips and nested rows |
| `--sw-ink` | `#193c34` | `#eef4e9` | Primary type and line icons |
| `--sw-ink-muted` | `#62756e` | `#adc3b7` | Secondary text, labels, notes |
| `--sw-stroke` | `#d9e1d7` | `#385146` | Dividers and card borders |
| `--sw-accent` | `#d7ed72` | `#d7ed72` | High-attention signal and illustration highlights |
| `--sw-accent-ink` | `#193c34` | `#193c34` | Type/icons on accent backgrounds |
| `--sw-headline-accent` | `#7a9843` | `#d7ed72` | Emphasized editorial headline spans |
| `--sw-preview-bg` | `#f9fbf6` | `#22352c` | Embedded application workspace |
| `--sw-preview-stroke` | `#e2e8dc` | `#395347` | Workspace dividers |
| `--sw-shadow` | Pine-tinted soft shadow | Dark neutral shadow | Floating preview depth |
| `--sw-button-bg` / `--sw-button-ink` | `#1b473a` / `#ffffff` | `#d7ed72` / `#193c34` | Primary filled controls |
| `--sw-button-hover` | `#285d49` | `#bed447` | Primary filled control hover |

The Mantine palette also supplies ten `pine` shades, from `#e7efec` to `#102a24`, and ten `lime` shades, from `#f8fce9` to `#65751e`. Mantine `primaryColor` is `pine`. Use semantic `--sw-*` roles for app-specific surfaces and text rather than copying literal colors into new features. Illustration-specific participant colors (peach, blue, gold, lilac), chart segments and the deliberately fixed dark-green showcase panel remain local to their page-level previews; they are not business status colors or product-wide semantics.

### Type, rhythm and geometry

- **UI type:** `Avenir Next, Avenir, Segoe UI, Arial, sans-serif` via Mantine `fontFamily`. Use UI sans for controls, tabular data, navigation and labels.
- **Display type:** `--sw-font-display: Georgia, Iowan Old Style, Times New Roman, serif`. Large, tightly tracked editorial headings and occasional italic spans distinguish the brand from the dense workspace UI. This is not a proposed default for financial tables.
- **Spacing:** Mantine `xs=8`, `sm=12`, `md=18`, `lg=26`, `xl=38` px. Component padding, grids and section spacing use the shared tokens where meaningful; oversized editorial spacing remains local.
- **Radius:** Mantine `xs=6`, `sm=10`, `md=16`, `lg=24`, `xl=999` px. Use pill controls, medium-radius business panels, and selective larger editorial cards. The window frame is deliberately slightly less rounded than a marketing card.
- **Width:** `--sw-content-width: 1220px` for sections and shared navigation; the homepage hero expands to `1380px` to give its application preview breathing room.
- **Borders/elevation:** Quiet one-pixel strokes distinguish nested financial surfaces. Use soft shadows sparingly, especially on floating previews, not on every row.

### Mantine customization

The theme centralizes `Button` pill radius, default `md` size and stronger label weight, and the small pill `NativeSelect` default. The supported `variantColorResolver` changes the **primary pine filled variant** to use semantic light/dark button colors; other Mantine variants and colors use Mantine's resolver. Avoid repeated per-page button/input overrides. `respectReducedMotion` is enabled; the document stylesheet also disables smooth anchor scrolling when the user requests reduced motion.

A future business screen should render ordinary controls through Mantine, and consume shared variables when composing custom expense or balance panels. It should not copy the public site's giant headlines, orbit graphics or decorative card shapes wholesale.

## Component and style ownership

| Scope | Implementation | Convention |
| --- | --- | --- |
| Document and provider | `src/frontend/app/layout.tsx`, `providers.tsx`, `globals.scss` | Theme script/provider and only genuinely global document styles |
| App-wide tokens | `src/frontend/lib/theme.ts` | Mantine colors, semantic roles, typography, component defaults and behavior |
| Reusable site patterns | `src/frontend/components/brand/`, `components/site-header/` | Shared wordmark, navigation and appearance selection; adjacent SCSS Modules |
| One-off landing composition | `src/frontend/app/page.tsx` and `page.module.scss` | Editorial sections, hero, marketing cards and footer composition |
| Business illustration only | `src/frontend/app/_components/` | Dashboard, allocation and financial-history mock views with their own SCSS Modules |
| Illustrative data | `src/frontend/app/_components/illustration-data.ts` | Literal fictional participant, expense and balance values, independent of contracts |

Do not extract illustration-only rows, fake sidebars or chart marks into application components until a real feature proves a reusable pattern. The app-wide foundation is deliberately narrow: visual semantics and genuinely shared navigation controls, not a speculative component library. The mock sidebar and financial UI labels are **not actionable navigation or controls**.

## Layout and accessibility

The desktop composition uses a large editorial hero next to one integrated mock workspace. Below it are a concise product rationale, an expense-detail composition, two supporting cards for settlements/balances, and an inverse history section. All navigation targets are real in-page anchors, including the skip link and back-to-top control; no signup or fake login action is shown.

Main breakpoint behavior in the current implementation:

- At **1020px**, desktop navigation changes to a Mantine Burger/Drawer menu, retaining the appearance selector.
- At **980px**, the hero stacks its copy over the workspace and larger grids begin to compress.
- At **730px**, content becomes single-column: allocation explanation, history preview and supporting cards stack naturally.
- At **660px**, the decorative dashboard sidebar disappears, preserving the expense and balance content.
- At **450px and below**, tighter type/spacing and mobile financial labels protect narrow viewports. The document minimum is 320px.

Structure includes one main landmark, section headings, explicit navigation labels, a keyboard-operable drawer and a skip link. Theme choice is labeled **Appearance** and exposed as a native select. The previews are figures/sections with explicit illustrative labels and no misleading operational buttons. Focus outlines, reduced motion and both scheme variants are centrally supported. Test accessibility with real browsers as well as jsdom: automated axe under jsdom cannot establish visual contrast or geometry.

## Example ledger and domain fidelity

The **Cabin weekend** group contains Ava, Ben, Cleo and Dani. Current fictional expenses are Cabin rental €240 (paid Ben), Groceries €120 (paid Ava) and Road snacks €40 (paid Cleo), each equally allocated across four people. Expenses total €400 with €100 allocated to each participant. Before settlement, balances are Ava +€20, Ben +€140, Cleo −€60, Dani −€100. After the **recorded external** transfer of €40 from Dani to Ben, the displayed balances become **+€20, +€100, −€60, −€60**, summing to zero.

The preview history depicts group revision 3 with Road snacks recorded, revision 4 correcting Groceries from €112 to €120 (the old record remains), and revision 5 recording the external settlement. These are descriptions of the [documented financial semantics](../data-model-design.md), not real transaction handlers, snapshots or database revision logic. ShareWise neither executes nor verifies a bank transfer; history is not proof that one happened.

## Review scope and tradeoffs

This is a **candidate**. It optimizes for friendly consumer credibility, financial readability and a distinctive marketing-to-product connection, while avoiding external image/font dependencies. The serif/system-font treatment is more editorial than a conventional finance dashboard; some teams may prefer a more strictly utilitarian visual language. The mock application is a compact illustration rather than a production screen spec, and static fixture amounts must not be mistaken for derived business calculations.

Review the accompanying desktop/mobile, light/dark captures alongside the implementation. The selected direction, if any, will be decided by the team at the **Phase B human review gate**. This document does not authorize Phase C, production integration, authentication or deployment migration.
