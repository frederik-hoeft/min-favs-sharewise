# ShareWise visual design: Quiet clarity

**Status:** Selected frontend design direction. The implemented pages demonstrate the foundation; the static financial examples and dummy application routes are not finalized business workflows.

ShareWise deals with money shared among people, so its interface should make both the numbers and their context easy to understand. **Quiet clarity** pairs generous spacing and direct language with compact, structured financial information. Neutral canvases let balances and history remain legible, while a restrained indigo accent indicates navigation, emphasis and action. Green, teal and olive are deliberately absent from the identity.

The public website and application share typography, surfaces, controls and color semantics, but they express different densities. The public page uses editorial headings, whitespace and a floating navigation surface to introduce the product. The workspace uses a docked header and sidebar to prioritize predictable navigation and usable screen area. The two shells are composed separately in Next.js; neither defines a competing theme.

## Color and typography

The [Mantine theme](../../src/frontend/lib/theme.ts) is the source of truth for exact palette values, radii, typography scales and component defaults. It defines a ten-step `indigoBrand` palette, replaces Mantine's `gray` and `dark` scales with ShareWise neutrals, and provides semantic CSS variables in both light and dark schemes. Light, dark and system appearance choices use Mantine's color-scheme provider and persistence.

| Semantic role | Design intention | Theme examples |
| --- | --- | --- |
| Canvas and panels | Distinguish page background, cards and subordinate surfaces without heavy borders | `--sw-page`, `--sw-surface`, `--sw-surface-subtle` |
| Content hierarchy | Main text remains strong; supporting information is quiet but readable | `--sw-ink`, `--sw-muted` |
| Boundaries | Subtle dividers separate regions without dominating them | `--sw-border` |
| Actions and selection | Indigo identifies primary actions and selected navigation; inactive hover uses a restrained neutral tint | `--sw-accent`, `--sw-action-ink`, `--sw-interactive-hover` |
| Inverse surfaces | Consistent dark closing/summary panels in either appearance mode | `--sw-inverse`, `--sw-on-inverse` |

Mantine's `cssVariablesResolver` maps shared semantic colors, while `variantColorResolver` provides legible primary filled foregrounds for buttons and active navigation in both schemes. Standard `Menu`, `NavLink`, `Button`, `Card`, `Paper` and `Burger` behavior is customized centrally through supported Mantine theme/component APIs when their application-wide presentation differs. Components should consume these roles instead of copying hex values or overriding Mantine's internal DOM structure.

Typography uses a local system font stack (`Inter`, `Segoe UI`, `Arial`, sans-serif) without network font downloads. Headings are bold with tight tracking; financial data uses aligned, tabular numerals. Mantine's spacing and radius scales control recurring geometry; local SCSS Modules compose individual layouts. Exact values belong in the theme or the relevant component stylesheet, where they remain maintainable as the design evolves.

## Components and responsive composition

Mantine provides standard controls, layout primitives and accessibility behavior. ShareWise-specific components wrap it only when they express a genuinely reusable responsibility, such as `Brand` or `ParticipantAvatar`. Page-specific illustrations remain private to the public route. Phosphor is the shared UI icon set; the overlapping-square ShareWise mark is an identity asset, not a general-purpose icon.

The public page uses a fixed, inset `AppShell.Header` and a centered content column. The workspace uses Mantine `AppShell` with `layout="alt"`: a full-height edge-attached sidebar, header and a content area centered in the remaining workspace. The sidebar adapts from a full navigation list to an icon rail at intermediate widths, then to a Mantine Drawer below the mobile breakpoint. Its controls reuse the same theme as the public page, even though the shells have distinct geometry.

Responsive breakpoints are named in [`styles/_breakpoints.scss`](../../src/frontend/styles/_breakpoints.scss); Mantine's `sm` and `lg` thresholds govern the application navigation states. Component-level rules stay in adjacent `*.module.scss` files, and shared Sass imports use the frontend `@/` alias. This keeps the layout adaptable without duplicating breakpoints or introducing global page selectors.

Navigation, appearance selection and mobile menus use Mantine's keyboard and focus semantics. Both shells provide a skip link, visible keyboard focus and appropriate landmarks. Icon-only controls have accessible labels; decorative icons are hidden from assistive technology. Financial direction is conveyed by signs and text, never by color alone. Reduced-motion preferences suppress nonessential movement. Browser inspection remains necessary for contrast, layout and interaction checks that jsdom cannot establish.

## Example content and scope

The landing page illustrates one coherent group: four participants share three expenses totaling €680, and an external €60 settlement is recorded separately. Balances and revisions remain internally consistent across the previews. The presentation demonstrates how real financial records could read in this design without performing calculations, contacting the backend, processing payments or implying that the demo is a working account.

These examples establish a visual vocabulary, not templates that every production feature must duplicate. New pages should reuse the theme and meaningful shared components, then design their own domain-facing workflows from actual requirements. See [frontend architecture](../architecture/frontend.md) for ownership and the [developer guide](../development/frontend.md) for implementation conventions.
