# ShareWise / warm architectural minimalism

**Status:** Phase B design candidate, **not approved for production adoption**. This reference describes the proposal implemented on `feature/p2.0e-warm-architectural-candidate`. The [P2.0e landing-page brief](../internal/tasks/p2.0e-frontend-design/landing-page-specification.md) remains the governing scope. Another candidate may be selected instead.

## Identity and visual grammar

The concept treats sharing as a matter of clarity and trust rather than urgency or finance-as-a-service. Warm chalk and stone fields provide a calm canvas; very dark charcoal typography establishes hierarchy; oxidized copper marks moments needing attention. Borders and restrained surface changes organize information instead of drop shadows or colorful widgets. The same visual language appears in the marketing page, group overview, expense allocation, journal and settlement illustration.

The composition follows a simple architectural rhythm: generous negative space outside the product preview, compact measured spacing within it, fine dividing lines and numeric information aligned in consistent columns. Typography uses an available system sans stack, with tight tracking for display headlines and ordinary spacing for dense business data. No fonts, images, assets or icons are fetched at runtime. This is deliberately an *application* visual language, not a mandate to apply landing-page headline sizes to finance screens.

The public-facing copy is specific about product boundaries: ShareWise **records** group expenses and payments exchanged **outside** the app; it does not send money or verify settlement claims. The previews are static editorial fixtures, not business workflows or commitments to particular functional screens.

## Central theme foundation

`src/frontend/lib/theme.ts` is the single owner of the candidate's palette, font stacks, radii, spacing, Mantine defaults and application semantic CSS variables. `src/frontend/app/providers.tsx` installs it through Mantine's provider and `cssVariablesResolver`, with `defaultColorScheme="auto"`; `src/frontend/app/layout.tsx` installs the early color-scheme script. Prefer Mantine props such as `c="dimmed"`, `variant="light"` and `color="copper"` when using standard components. Custom application compositions use the semantic `--sw-*` tokens rather than hardcoded page palettes.

### Palette and semantic roles

| Role / variable | Light | Dark | Purpose |
| --- | --- | --- | --- |
| `--sw-canvas` | `#f6f3ed` | `#1e1c1a` | Main page background |
| `--sw-paper` | `#fffdfa` | `#282522` | UI preview cards and elevated reading surfaces |
| `--sw-raised` | `#f0ebe3` | `#322e2a` | Distinct content regions and subdued panels |
| `--sw-ink` | `#25221f` | `#f5f0e9` | Primary typography and key framing |
| `--sw-secondary` | `#645d54` | `#c4baae` | Body/supporting information |
| `--sw-faint` | `#645d54` | `#a89d91` | Quiet labels, timestamps and metadata |
| `--sw-line` | `#ded8cf` | `#49433d` | Structural dividers and container edges |
| `--sw-accent` | `#93472f` | `#dca084` | Emphasis, selected state, decorative marks |
| `--sw-accent-tint` | `#f5e5db` | `#443126` | Soft accent backgrounds |
| `--sw-positive` | `#765642` | `#ddb6a0` | Positive ledger positions, accompanied by explicit plus signs |
| `--sw-negative` | `#655e56` | `#c4baae` | Negative positions, accompanied by explicit minus signs |
| `--sw-emphasis-bg` | `#292723` | `#473429` | Strong metric/overview surface |
| `--sw-emphasis-fg` | `#fff9f1` | `#fff9f1` | Text on strong metric surface |
| `--sw-feature-bg` | `#282521` | `#282521` | Invariant dark feature strip, regardless of scheme |
| `--sw-feature-fg` | `#fffdfa` | `#fffdfa` | Text in invariant dark feature strip |

Mantine color scales include `copper[0..9]` and `stone[0..9]`; `copper` is the primary color (`primaryShade` 7 light / 6 dark). Use semantic colors for business meaning rather than assuming brown/copper denotes a negative or positive balance. Every amount has a textual sign and is not identified by color alone.

The invariant feature strip retains its deliberately dark identity in light and dark schemes. Its supporting illustration has fixed local background, line and muted text colors; these are local art-direction details, not additional globally approved tokens.

### Type, spacing, radius and surfaces

- **Type:** `"Avenir Next", "Segoe UI", Arial, sans-serif` for display and body; the monospace fallback is `"SFMono-Regular", Consolas, monospace`. No web fonts or `next/font` downloads. Large landing headlines are page-local; product previews use tighter sizes and `font-variant-numeric: tabular-nums` for amounts.
- **Spacing:** Mantine `xs=8`, `sm=12`, `md=16`, `lg=24`, `xl=32` px. The landing page intentionally adds larger page-local vertical whitespace; those values are not application-wide spacing tokens.
- **Radius:** Mantine `xs=4`, `sm=6`, `md=10`, `lg=16`, `xl=24` px. Standard controls use the theme's `md` default. A restrained `sm` radius is configured centrally for `Button`; `Card` defaults to a `lg` radius with border.
- **Surfaces:** Prefer outlines and `--sw-line` separation over shadows. Product details use `--sw-paper`, contextual sections use `--sw-raised`, strong headline metrics use `--sw-emphasis-bg`.
- **Content widths:** `--sw-content-width: 1200px`. The landing page adds responsive gutters through its own module.

Standard Mantine buttons, badges and segmented controls retain their standard behavior and appearance, with only palette, radius and very limited component defaults. Do not copy the preview's bespoke financial row styling onto ordinary Mantine inputs, buttons or tables.

## Component ownership and examples

| Layer | Current implementation | Ownership rule |
| --- | --- | --- |
| Global theme | `lib/theme.ts`; `app/providers.tsx` | Single source for colors, semantics and Mantine component defaults |
| Global document | `app/globals.scss`; `app/layout.tsx` | Body background, selection, visible focus, reduced motion and scheme script only |
| Reusable identity | `components/brand.tsx` + adjacent module | Wordmark/symbol usable in future public or app shells |
| Public navigation | `components/site-header.tsx` + adjacent module | Anchors, brand and navigation for public pages; no routes invented |
| Color-scheme control | `components/theme-picker.tsx` | Mantine `SegmentedControl` with **Auto / Light / Dark** choices and persisted scheme |
| Landing composition | `app/page.tsx` + `app/page.module.scss` | Hero, narrative sections, responsive rhythm, decorative elements and footer |
| Illustrative finance views | `app/_components/*` + adjacent modules, `app/_data/example.ts` | Page-owned mock visualizations, not reusable business implementations |

The previews remain within the landing feature because they are **not** production expense, balance or history components. Do not import fixture types into finance modules or build API contracts from these displays. If future application pages independently need the same ledger row or group summary, extract it then against the real domain and accessibility requirements.

The editorial fixture has four people, three equal-split expenses totaling **€500.00**, two reported outside settlements totaling **€80.00**, and group balances summing to **€0.00**. The rental's displayed second revision changes €300.00 to €320.00; its four current allocations are €80.00 each. This makes the example coherent without introducing finance calculation functions into the landing page.

## Responsive, interaction and accessibility conventions

The layout scales with a 1200 px content maximum and collapses to one-column compositions below **800 px**; denser previews simplify at **650 px** and the landing's three-column principles stack below **560 px**. The header reduces links at **840 px**, but preserves access to all sections through its `Explore` anchor and footer navigation. Long content and mock UI must remain within the viewport at 390 px and narrower. Small informational captions are subordinate; primary marketing copy and real navigation remain legible.

Actual links are in-page anchors (`#workspace`, `#approach`, `#top`) and a keyboard skip link (`#main-content`). No login, API demo, fictitious account creation or finance buttons are present. The preview sidebars and status text are **not** interactive controls. Each preview has a descriptive `section` label, and amount values are textual. Prefer Mantine's documented accessibility behavior for real controls, keeping visible keyboard focus via the global `:focus-visible` rule and preserving reduced-motion preferences.

Mantine's `ColorSchemeScript` chooses light/dark before hydration. The theme control operates on the actual `auto`/`light`/`dark` preference via `useMantineColorScheme`; Mantine's built-in localStorage manager ignores unrecognized stored values and tolerates failed writes. No separate bespoke theme state is maintained. The candidate relies on Mantine's global color-scheme styling and CSS-variable resolver for coherent system-mode rendering.

## Candidate constraints and future review

This reference covers only the independently produced P2.0e **Phase B candidate**. The page's visualized controls and financial history do not represent implemented business functionality, and the candidate has not been selected. The legacy greeting page, server-only API route and Next `output: "standalone"` stay unchanged. Full-application static export, authentication, API transport and functional group/finance screens belong to later tasks. The team's review should consider responsive readability, dense table usability and measured contrast alongside the overall impression before accepting any palette or layout decisions.
