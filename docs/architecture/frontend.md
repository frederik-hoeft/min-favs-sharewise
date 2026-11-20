# Frontend architecture

ShareWise's frontend has two independent responsibilities: it presents the product and workspace through Next.js routes, and it provides a browser-side boundary to the Spring API. Route structure determines where an experience is presented; it does not define business ownership. The backend remains authoritative for identities, group membership, expenses, balances, settlements and financial history.

The implemented frontend is a foundation for those workflows. The public site uses static financial illustrations, the application workspace contains demonstration routes, and neither experience is authenticated. A shared browser HTTP client and same-origin development API forwarding are functional infrastructure, although no domain feature currently consumes them.

## Route composition and UI ownership

The App Router separates document-wide concerns from the two experiences. The root layout installs the Mantine provider, color-scheme initialization and global document styles. Below it, the public and workspace route groups each own an AppShell and their navigation model. Route groups affect layout composition but do not contribute URL segments.

```text
app/
├── layout.tsx                 Shared document and providers
├── (public)/
│   ├── layout.tsx             Public AppShell
│   ├── page.tsx               /
│   └── _components/           Landing-page presentation
└── (app)/
    └── app/
        ├── layout.tsx         Workspace AppShell
        ├── page.tsx           /app
        ├── dummy1/page.tsx    /app/dummy1
        ├── dummy2/page.tsx    /app/dummy2
        ├── dummy3/            Nested breadcrumb demo and ancestor routes
        └── _components/       Workspace navigation and shell behavior
```

The public shell provides an inset, fixed header, in-page navigation and a footer. Its expense, settlement and history previews are presentational illustrations backed by static example data. The workspace shell uses Mantine's `AppShell` with an edge-attached sidebar and header; the primary content is centered in the space left by navigation. The sidebar is expanded on wide screens, becomes an icon rail at intermediate widths and is replaced by a Drawer on mobile. Breadcrumbs reflect the workspace route: they retain the root and current location, and when the full hierarchy exceeds the available width, a navigable menu contains the omitted intermediate locations. The current location stays on one line within the fixed-height header; if its label is truncated, a popover exposes the full text on mouse, touch or keyboard activation. The account menu currently exposes appearance selection. The two experiences share the same theme without coupling their navigation or layout state.

UI ownership follows the smallest meaningful boundary. Route-local `_components/` folders hold compositions whose semantics belong to a particular page or shell; Next.js excludes these folders from routing, but they are not TypeScript-private. `components/` holds application-wide presentation responsibilities, such as branding, participant identity and appearance controls. Each component's SCSS Module owns its local styling; shared Sass utilities provide common responsive definitions. The global stylesheet covers document-wide concerns only.

This distinction also defines the React boundary: layouts and pages can remain Server Components, while interactive menus, theme controls and responsive navigation use Client Components. Domain-independent presentation components do not become domain services merely because several pages use them.

## Browser API boundary

`lib/api/` owns HTTP transport behavior for browser callers. The client normalizes paths beneath `/api`, uses same-origin credentials and uncached requests by default, serializes JSON request bodies, and decodes JSON or text responses. Successful `204` responses resolve without a body. HTTP failures become `ApiError` values; Spring `ProblemDetail` payloads retain their error code and optional validation-field errors. Network failures produce a typed error with status `0`.

The client accepts request and unauthorized-response hooks. An unauthorized hook may authorize **one** retry after a `401`; repeated unauthorized responses do not trigger an unbounded renewal loop. These hooks establish a transport extension point, not an implemented IAM session, CSRF protocol or token lifecycle.

The client is independent of the page that invokes it: callers supply an API path and receive a typed result or error. No business-specific adapter is currently connected. During local development, `next.config.ts` rewrites same-origin `/api/:path*` requests to the Spring backend at `BACKEND_URL`. Ordinary Next.js builds retain standalone-server output. An explicit production export mode generates static HTML and assets for the Nginx-only frontend image; Nginx will also own the production `/api/*` reverse proxy. This preserves the browser-facing URL and keeps business logic out of Next.js route handlers. The production proxy configuration and TLS integration belong to the subsequent infrastructure phase.

## Domain ownership and extension boundary

The intended application structure reserves `core/<domain>/` outside the route tree for presentation-independent models, endpoint adapters and application operations. This directory does not yet exist. Its domain-first organization reflects backend business boundaries, while allowing several routes to consume the same domain-facing functionality without importing one another's page code.

The dependency direction is from routes and reusable UI toward domain-facing operations, and from domain HTTP adapters toward `lib/api/`. Transport-wide mechanics remain in `lib/api/`; domain-specific endpoint paths and data contracts belong to the domain adapter. A direct adapter is sufficient when an operation has no additional application behavior; a separate service represents real orchestration, not automatic delegation. UI state, navigation and display formatting remain presentation concerns. The Spring backend continues to evaluate authorization and financial semantics rather than relying on browser calculations.

The frontend's tests form a separate TypeScript/Vitest project under `tests/`, with API-client, component and accessibility coverage. Production code lives outside that project, and production TypeScript checking excludes test sources. Tests and application code share the frontend npm dependency installation, not a separate dependency graph.

The [visual design foundation](../design/sharewise-design-foundation.md) describes the appearance shared by the shells. The [frontend development guide](../development/frontend.md) contains commands, implementation conventions and validation practices; the [stack overview](../stack/overview.md) places the browser boundary in the wider deployment.
