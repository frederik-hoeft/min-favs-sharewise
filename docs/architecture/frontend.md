# Frontend ownership and module organization

ShareWise's frontend separates **route-owned presentation** from **shared UI** and, as business features are implemented, **presentation-independent application logic**. The Next.js App Router owns navigation and page composition, not the structure of the application's business modules. Domain-oriented code lives outside `app/`, so its lifetime and dependencies do not follow the route tree.

This document describes the current component boundaries and the intended organization for future frontend services. The present landing page contains static financial illustrations; production group and finance clients, models and application services have **not** been implemented. The [selected visual foundation](../design/sharewise-design-foundation.md) owns the actual Mantine theme, styling conventions and existing presentation components. The [backend architecture](../architecture.md) and [domain model](../data-model-design.md) remain authoritative for business ownership and financial invariants.

## Presentation ownership

The current directories under `src/frontend/` have distinct responsibilities:

| Location | Ownership |
| --- | --- |
| `app/` | Next.js routes, layouts, providers, page composition and route-local presentation |
| `app/(public)/` | Public route group with its own AppShell layout, navigation, footer and landing-page previews |
| `app/(app)/app/` | Application route group and `/app` URL subtree; shared AppShell, responsive navigation and route-owned feature pages |
| `components/` | Reusable application UI, grouped by meaningful responsibility (`brand/`, `navigation/`, `participants/`, `theme/`) |
| `lib/` | Cross-cutting, non-domain infrastructure or configuration, currently the Mantine theme |
| `styles/` | Shared Sass utilities; component- and page-specific styling remains in adjacent SCSS Modules |
| `tests/` | Separate Vitest/TypeScript test project, outside production routes and components |

A route can own a private `_components/` directory (and, when needed, `_lib/` for route-specific non-visual presentation helpers). A component stays close to its owning page while it represents that page's layout, illustration or workflow. A component moves to `components/` when it expresses a stable UI responsibility that other routes can realistically reuse. Grouping shared components by their subject, as with `components/participants/ParticipantAvatar.tsx`, makes ownership clearer than a flat collection of generic widgets. Extraction is not required merely because two elements use similar markup.

Next.js treats `_components/` and other underscore-prefixed folders under `app/` as **private routing folders**: they are excluded from route matching, but are not TypeScript visibility or security boundaries. Parenthesized route groups such as `(public)` and `(app)` do not contribute URL segments; the `app/` folder beneath `(app)` **does** create the `/app` URL prefix. Files such as `page.tsx` and `layout.tsx` are Next.js route conventions; neighboring `*.module.scss` files provide locally scoped styling. Application-wide standard controls and visual tokens are configured through Mantine rather than duplicated in these local styles.

## Application layouts

The top-level `app/layout.tsx` owns the document, metadata and Mantine providers, not the application navigation. The public route group, `app/(public)/layout.tsx`, configures Mantine `AppShell` with a fixed header and `AppShell.Main` (the sole semantic `<main>` landmark). Its public navigation and footer live in `app/(public)/_components/`, alongside the landing-page-specific illustrations. Mantine owns the main-content offset; the page owns only in-page anchor clearance and section layouts. The shared `components/navigation/ShellHeader` provides the elevated, inset `AppShell.Header` surface, without embedding public navigation or any login state. Use Mantine's separately exported `AppShellHeader` and `AppShellMain` components when composing the shell from Next.js Server Components; reading compound properties such as `AppShell.Header` across the Server/Client Component boundary is not reliable.

The application subtree, `app/(app)/app/`, owns a separate `ApplicationShell` using Mantine's `layout="alt"`. The edge-attached `AppShell.Navbar` occupies the full viewport height, with the ShareWise brand at its top; `AppShell.Header` starts immediately beside it, with breadcrumbs and the account menu. On mobile, the navbar disappears and the brand symbol in the full-width header opens the navigation drawer. Mantine supplies the fixed-section geometry and offsets for `AppShell.Main`, the sole semantic main landmark. A `<Container size="content">` centers route content **within the space remaining beside the sidebar**, regardless of viewport width. An accessible scroll-to-top control covers unused header space beneath interactive controls. Unlike the public site's centered floating header, application shell surfaces are flush with the viewport edges and separated by subtle borders rather than elevation.

The shell has three responsive states: below Mantine `sm` (48rem), a branded Mantine `Drawer` provides navigation with backdrop, focus management and Escape-key dismissal; from `sm` to `lg` (75rem), the persistent navbar is an icon rail whose surface expands over the workspace on pointer hover or keyboard focus without shifting content; from `lg` upward, it is a fully labeled sidebar. Selecting a navigation destination collapses the expanded rail immediately, even if the pointer remains over it; pointer expansion becomes available again after leaving and re-entering, while keyboard focus can still expand it. The same `AppNavigation` links power all three states. Shell sizes are shared tokens in `lib/theme.ts`, and app-specific styling lives with `ApplicationShell`. The account avatar opens a Mantine menu with an Appearance subview and a Back item; the Light/Dark/System controls reuse the public appearance options. `SkipLink`, `Brand`, appearance options and the Mantine theme are shared with the public site, while the public floating `ShellHeader` and both navigation compositions remain owned by their respective layouts.

This is **an unauthenticated demo skeleton**, not a protected application or an implemented login flow. `/app`, `/app/dummy1` and `/app/dummy2` contain static placeholders; future features replace route content and extend navigation without replacing the layout. Authentication and optional `AppShell.Aside` are deliberately absent. A single root AppShell that switches between marketing and application states would conflate unrelated navigation concerns. The unrelated greeting starter remains outside both route groups.

## Future application and domain services

For new business workflows, use a **domain-first** organization outside the App Router. The following is an illustrative target shape, not a directory structure or set of services that already exists:

```text
src/frontend/
├── app/
│   ├── (public)/                     # Public AppShell layout and routes
│   └── (app)/                        # Application layout group (implemented)
│       └── app/                      # URL prefix /app
│           ├── _components/          # Application shell and navigation
│           └── groups/              # Example future feature route
│               ├── page.tsx
│               └── _components/      # Route-owned presentation
├── components/
│   └── participants/                   # Shared UI patterns
├── core/                               # Introduce with the first real workflow
│   ├── groups/
│   │   ├── model/                      # Presentation-independent types
│   │   ├── api/                        # Group REST client and transport contracts
│   │   └── services/                   # Group application orchestration, if needed
│   └── finance/
│       ├── model/
│       ├── api/
│       └── services/
├── lib/
│   ├── theme.ts                        # Existing shared theme
│   └── http/                           # Shared HTTP infrastructure, if needed
├── styles/
└── tests/
```

**Domain first** keeps a group's models, transport adapter and operations together instead of distributing them across global `api/groups/`, `models/groups/` and `services/groups/` trees. These domain folders are frontend modules, not Next.js routes or copies of Spring Modulith's Java package structure. Add only the subdirectories that serve a concrete responsibility; a simple typed read-only API client does not require a separate pass-through service or an interface for every class.

The intended dependency direction is:

1. **Presentation** (`app/` and `components/`) handles routes, interaction, rendering, UI state and formatting. It consumes typed domain-facing operations or data, and remains free of transport details where a meaningful mapping exists.
2. **Frontend application/domain code** (`core/<domain>/`) handles presentation-independent data types, input preparation, client-side validation where appropriate, and orchestration of calls. Services represent behavior, not generic containers for every fetch or pure helper.
3. **API adapters** (`core/<domain>/api/`) own endpoint paths, request/response DTOs, serialization and transport-specific error translation. Shared HTTP mechanics, when genuinely needed across domains, live in `lib/` and are consumed by these adapters.

Transport DTOs are not automatically application models: map them at the boundary when the representations or responsibilities differ. Conversely, avoid creating identical layers solely to enforce a pattern. Shared formatting for UI belongs with presentation, not in `core/`; keep route-specific formatters near the route and extract cross-route formatting helpers only when reused. `core/` must not import React components, `next/navigation`, route modules or page styling. Dependencies between domains use deliberate public operations and types rather than another domain's internal adapter implementation.

The backend remains authoritative for permissions, expense allocation, balances, settlements, financial revisions and persistence. Frontend services may coordinate requests and adapt returned data, but do not reproduce backend financial policy or treat client-side validation as authorization. The frontend is intended for static export: business features call the backend through the documented same-origin API boundary, not through Next.js server-only routes. The existing greeting route is unrelated starter infrastructure.

## Testing and evolution

Component and interaction tests belong to the dedicated `tests/` project, not alongside production TSX files. As domain modules appear, test their pure logic and API-boundary behavior in the same test project, with test layout reflecting the owned module or route where useful. Browser-level checks still cover responsive layout and behavior that jsdom cannot establish.

This structure is deliberately incremental. The route/shared-component split and both AppShell layouts are in use now; `core/` and additional infrastructure directories are established only when a real business feature gives them meaningful contents. Reassess a boundary when reuse, data flow or ownership changes rather than keeping a speculative framework in place.
