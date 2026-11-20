# ShareWise visual design: Quiet clarity

**Status:** Selected visual foundation for the implemented public site and demonstration workspace. Their financial previews and placeholder routes are not working business features.

ShareWise presents decisions about money shared among people. Its visual language puts the amount, the people involved and the history of a decision in a clear hierarchy. **Quiet clarity** combines direct typography and generous whitespace with structured, compact records. Neutral surfaces provide the canvas; restrained indigo identifies actions, selected navigation and important emphasis. The identity contains no green, teal or olive accents.

The public and workspace experiences express the same design at different densities. The public site uses editorial scale and an inset floating header to introduce the product. The standalone authentication layout uses the same theme with a minimal linked brand and form, independent of landing-page navigation. The workspace uses an edge-attached sidebar, header and centered content area to keep navigation predictable while leaving room for data-rich views. The distinction is compositional: both shells resolve their colors, controls and typography from the same Mantine theme.

## Theme and color semantics

[`lib/theme.ts`](../../src/frontend/lib/theme.ts) defines the global Mantine theme and its CSS-variable resolver. It owns the indigo primary palette, ShareWise replacements for Mantine's `gray` and `dark` palettes, typography and spacing scales, radii, and defaults for standard components. Semantic variables bridge those palettes to page and application surfaces in light and dark modes.

| Role | Meaning | Examples |
| --- | --- | --- |
| Canvas and surfaces | Page background, panels and subordinate regions | `--sw-page`, `--sw-surface`, `--sw-surface-subtle` |
| Content and boundaries | Primary text, supporting text and subtle separators | `--sw-ink`, `--sw-muted`, `--sw-border` |
| Interaction | Primary action, action foreground and understated hover | `--sw-accent`, `--sw-action-ink`, `--sw-interactive-hover` |
| Feedback | Consistent, restrained validation/error foreground, border and surface | `--sw-danger-ink`, `--sw-danger-border`, `--sw-danger-surface` |
| Inverse presentation | Closing and summary surfaces that remain dark in either scheme | `--sw-inverse`, `--sw-on-inverse` |

The Mantine provider applies a light, dark or system-derived color scheme. `cssVariablesResolver` supplies scheme-specific semantic values and maps Mantine's common text and neutral interaction roles to them. `variantColorResolver` gives filled primary controls a legible foreground, including the different foreground needed by the lighter dark-mode indigo. As a result, a selected navigation link and a primary Button share a color contract rather than maintaining separate selection palettes.

Standard components inherit the common foundation. Mantine component extensions establish application-wide defaults for inputs, Alerts, Buttons, NavLinks, Menus, Cards, Paper and Burger controls. Inputs share a smaller radius and native email-field presentation; field descriptions remain visible above the input while validation errors appear below it. Error alerts use restrained danger colors in both schemes; form fields use the same semantic error foreground instead of independently choosing reds. Component-specific customization remains where the component has a distinct semantic responsibility, such as a dropdown surface; the application does not restyle Mantine's internal markup independently on each page. Exact palette values and dimensions live in the theme or owning SCSS Module, where changes remain consistent across consumers.

The font stack is local (`Inter`, `Segoe UI`, `Arial`, sans-serif), without remote font loading. Strong headings and aligned tabular numbers establish the hierarchy for financial information. Spacing and radii come from Mantine scales; SCSS Modules describe compositions that do not correspond to standard controls.

## Layout, navigation and responsive behavior

The public site's fixed `AppShell.Header` is inset and centered above an editorial content column. The workspace's `AppShell` uses `layout="alt"`, placing the sidebar along the full left edge and the header beside it. Main content is centered within the remaining workspace, not the entire viewport, so it stays visually centered as navigation changes width.

Workspace navigation has three forms: a persistent expanded sidebar on wide screens, a collapsible icon rail at intermediate widths and a Mantine Drawer on mobile. Rail expansion overlays content rather than changing its layout position. Selecting a route collapses the rail, including when the pointer remains over its previous position. On mobile, the hamburger belongs to the header and the brand appears inside the navigation drawer. The public shell retains its own header and links, independent of workspace navigation state.

The responsive thresholds come from the shared Sass breakpoints and Mantine theme. Presentation-specific spacing remains local to each shell or component; it is not part of the frontend's architectural contract. Shared components represent recurring interface responsibilities, while the public financial previews retain their local composition. Phosphor provides application icons, and the custom overlapping-square mark identifies ShareWise itself.

Accessibility is part of this presentation model. Both shells provide landmarks, skip navigation, labeled icon-only controls and visible keyboard focus. Mantine handles the menu and Drawer interaction semantics. Financial meaning survives without reliance on color: signs, labels and descriptions convey amounts and direction. Nonessential movement respects reduced-motion preferences.

## Illustrative financial content

The landing page depicts one consistent fictional group of four participants. Its three shared expenses total €680, while an external €60 settlement is recorded separately; the balance and history previews refer to that same example. They demonstrate the intended readability of allocations, settlement records and revisions without requesting live data, calculating balances or processing payments.

These previews establish a visual vocabulary rather than a data contract. Real workflows remain governed by the [frontend application boundaries](../architecture/frontend.md) and the backend [domain model](../data-model-design.md). Contributor conventions and the checked-in Mantine reference are linked from the [frontend development guide](../development/frontend.md).
