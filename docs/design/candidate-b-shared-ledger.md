# ShareWise candidate B: Shared Ledger

**Status:** Phase B design candidate for team comparison, not an approved design system.

## Concept and use

Shared Ledger pairs an approachable, editorial public face with practical, restrained financial interfaces. The public page uses generous whitespace, a prominent serif accent in headlines, soft botanical colors and direct language; the illustrated application uses compact, legible records and exact monetary values. The ledger motif appears in the mark (two offset entries), fine rules, aligned amounts, and a vertical record of changes. It should feel useful and trustworthy without impersonating a bank or pretending ShareWise processes payments.

The prototype lives at [`src/frontend/app/page.tsx`](../../src/frontend/app/page.tsx). Its single fictional group and financial change sequence are in [`app/_data/preview.ts`](../../src/frontend/app/_data/preview.ts). The UI shows a group with four participants, three expenses, an equal-share example, a **recorded external** settlement and a historical snapshot. These are static editorial examples, not the implementation of an expense or balance algorithm. The page deliberately has no fake sign-in, payment, invite or app-launch buttons.

## Visual foundations

The centrally defined [`lib/theme.ts`](../../src/frontend/lib/theme.ts) supplies a ten-step `forest` primary palette and a `clay` secondary palette, system UI type, default radii, reduced-motion preference and Mantine component defaults. `Button` uses the theme's common pill radius and weight, with automatic filled-control text contrast. Other stock controls retain Mantine's accessible semantics and behaviors. Common controls must be changed here, not restyled repeatedly in pages. `Providers` passes the theme and its CSS-variable resolver into the root `MantineProvider`.

| Semantic CSS variable | Light | Dark | Meaning |
| --- | --- | --- | --- |
| `--sw-page` | `#f8f8f3` | `#101c18` | Website background |
| `--sw-paper` | `#ffffff` | `#1a2923` | App cards and elevated product surfaces |
| `--sw-elevated` | `#fcfdf9` | `#21332b` | Subtle alternate card or navigation surface |
| `--sw-brand` | `#225d48` | `#6fa98b` | Brand mark fill against the page |
| `--sw-ink` | `#1b3029` | `#f1f5ed` | Primary copy |
| `--sw-muted` | `#60746a` | `#b4c4b9` | Secondary copy and metadata |
| `--sw-line` | `#dfe6db` | `#34483d` | Borders, dividers and hairlines |
| `--sw-hero` | `#e8f0e6` | `#1a3127` | Large quiet editorial surfaces |
| `--sw-highlight` | `#e4f1e5` | `#244939` | Active and supporting accents |
| `--sw-warm` | `#f5e7d4` | `#483829` | Warm editorial contrast |
| `--sw-positive` | `#1b6a50` | `#a3ddbc` | Positive balance and meaning-bearing green |
| `--sw-negative` | `#a45b42` | `#ecab91` | Negative balance and meaning-bearing clay |
| `--sw-deep` / `--sw-deep-ink` | `#193c32` / `#f8f8ef` | `#0c1613` / `#f1f5ed` | Dedicated inverse editorial area |

`--sw-hero-accent`, `--sw-warm-ink`, `--sw-shadow` and the display font are also defined centrally. Prefer semantic roles to raw palette shades in new custom components. Mantine's generated `--mantine-*` variables provide spacing, radii, controls, and occasional explicit palette accents. `--sw-content-width: 1200px` sets the maximum composition width. All role values are generated through Mantine's supported `cssVariablesResolver`, including light/dark variants, rather than maintained by global selector overrides.

The UI font is a locally available system stack (`Avenir Next`, `Segoe UI`, Arial); financial values use tabular numerals. Headline emphasis uses locally available Georgia / Times serif with italic style. No external font asset or font-loading service is required. The type system uses bold, tightly tracked headings and quiet, spacious explanatory copy. Typical desktop spacing is 70–120 px between major sections, 24 px inside cards, 8–16 px for dense ledger information. The Mantine base spacing scale is 8/12/16/24/32 px and radii range from 4 to 24 px.

## Component and style ownership

- `lib/theme.ts` is the application-wide owner of visual tokens and standard Mantine component defaults. `app/globals.scss` contains only document body, selection, focus and reduced-motion behavior.
- [`components/brand/`](../../src/frontend/components/brand/) provides the reusable mark/wordmark. [`components/theme-selector/`](../../src/frontend/components/theme-selector/) provides the reusable appearance control, built with Mantine `NativeSelect`; it stores `light`, `dark` or `auto` through Mantine's color-scheme manager, which supports localStorage failure fallback and system preference. The existing root `ColorSchemeScript` minimizes theme flash before hydration.
- [`components/participant-avatar/`](../../src/frontend/components/participant-avatar/) maps typed participant tints to Mantine `Avatar` colors without duplicating avatar palettes across features.
- `app/_components/workspace-preview` and `app/_components/feature-stories` are **marketing-local** illustrative composites, not production Group or Finance widgets. They read immutable fixtures from `app/_data/preview.ts`. Keep them adjacent to their SCSS Modules until a real application workflow establishes a reusable contract.
- `app/page.module.scss` owns the hero, section composition, responsive layout, editorial inverse panel and footer. Do not turn its marketing-specific styling into global overrides. Product previews use Mantine `Paper`, `Badge`, and `Avatar` for appropriate standard elements, with local styling only for preview-specific structure.

A future real expense component should consume domain-facing data through an application interface and use shared Mantine tokens, but should **not** reuse a fabricated prototype fixture or inherit static preview layouts as an implicit requirement.

## Responsive and accessible behavior

The wide hero places copy beside a mock group overview, shifting into a single column at **900 px**. Editorial sections follow the same principle. The three feature stories become two and then one column at **900 px / 610 px**. The full application preview drops its illustrative sidebar at **600 px**; at **370 px**, the balance list becomes a single column instead of clipping amounts. The header uses a native keyboard-accessible `details` menu below **900 px**. Container gutters shrink from 40 to 17 px for narrow viewports. Real navigation uses same-page anchor targets only.

A visible skip link, semantic `main`, named navigation regions, labeled product examples, meaningful heading order and native links provide keyboard-accessible structure. Preview navigation and finance visuals are text or static graphics, intentionally not focusable controls. The appearance menu and its selection are the only client-side page behavior; system is the default. Reduced-motion settings disable smooth scrolling and decorative rotations/transitions. Contrast must be evaluated in a real browser because jsdom/axe cannot calculate CSS color contrast reliably.

## Trade-offs and review questions

The restrained, nature-inspired palette favors reassurance and clarity over high-energy startup marketing. The app illustration is intentionally denser than the public copy, which provides a useful preview of future finance screens without defining their final layout. The logo, headline voice, role colors, and illustrative records are candidate choices. Before approval, compare this candidate with alternatives for brand distinctiveness, long-form financial readability, small-screen data density, and the appropriateness of editorial serif accents across the broader authenticated application.

The landing page is statically rendered and does not request backend services. Production `output: "standalone"`, `/api/greetings`, and `/greetings` are inherited starter infrastructure and remain outside Phase B scope. A disposable export probe can demonstrate the design's export compatibility without modifying those routes.
