# Frontend architecture

ShareWise's frontend has two independent responsibilities: it presents the product and workspace through Next.js routes, and it provides a browser-side boundary to the Spring API. Route structure determines where an experience is presented; it does not define business ownership. The backend remains authoritative for identities, group membership, expenses, balances, settlements and financial history.

The implemented frontend is a foundation for those workflows. The public site uses static financial illustrations, and the application workspace contains demonstration routes rather than protected business data. The authentication pages and identity coordinator use the production IAM registration, login, refresh, logout and CSRF endpoints when the backend is configured with IAM signing trust. Browser-side identity tests use an injected mock transport to exercise renewal, errors and cross-tab coordination independently of the deployed API. A shared browser HTTP client and same-origin development API forwarding provide the transport boundary.

## Route composition and UI ownership

The App Router separates document-wide concerns from the public, authentication and workspace experiences. The root layout installs the Mantine provider, color-scheme initialization and global document styles. The public and workspace route groups each own an AppShell and their navigation model; the authentication group owns a smaller, independent shell. Route groups affect layout composition but do not contribute URL segments.

```text
app/
├── layout.tsx                 Shared document and providers
├── (public)/
│   ├── layout.tsx             Public AppShell
│   ├── page.tsx               /
│   └── _components/           Landing-page presentation
├── (auth)/
│   ├── layout.tsx             Minimal authentication shell
│   ├── auth/login/page.tsx    /auth/login
│   ├── auth/register/page.tsx /auth/register
│   └── _components/           Credential forms and validation
└── (app)/
    └── app/
        ├── layout.tsx         Workspace AppShell
        ├── page.tsx           /app
        ├── account/page.tsx   /app/account (own-account profile)
        ├── dummy1/page.tsx    /app/dummy1
        ├── dummy2/page.tsx    /app/dummy2
        ├── dummy3/            Nested breadcrumb demo and ancestor routes
        └── _components/       Workspace navigation and shell behavior
```

The public shell provides an inset, fixed header, in-page navigation and a footer. Its expense, settlement and history previews are presentational illustrations backed by static example data. The workspace shell uses Mantine's `AppShell` with an edge-attached sidebar and header; the primary content is centered in the space left by navigation. The sidebar is expanded on wide screens, becomes an icon rail at intermediate widths and is replaced by a Drawer on mobile. Breadcrumbs reflect the workspace route: they retain the root and current location, and when the full hierarchy exceeds the available width, a navigable menu contains the omitted intermediate locations. The current location stays on one line within the fixed-height header; if its label is truncated, a popover exposes the full text on mouse, touch or keyboard activation. The account menu links to the Account page and retains its Appearance submenu; the account route appears in breadcrumbs without adding a sidebar entry. All three experiences share the same theme without coupling their navigation or layout state.

UI ownership follows the smallest meaningful boundary. Route-local `_components/` folders hold compositions whose semantics belong to a particular page or shell; Next.js excludes these folders from routing, but they are not TypeScript-private. `components/` holds application-wide presentation responsibilities, such as branding, participant identity and appearance controls. Each component's SCSS Module owns its local styling; shared Sass utilities provide common responsive definitions. The global stylesheet covers document-wide concerns only.

This distinction also defines the React boundary: layouts and pages can remain Server Components, while interactive menus, theme controls and responsive navigation use Client Components. Domain-independent presentation components do not become domain services merely because several pages use them.

## Browser API boundary

`lib/api/` owns HTTP transport behavior for browser callers. The client normalizes paths beneath `/api`, uses same-origin credentials and uncached requests by default, serializes JSON request bodies, and decodes JSON or text responses. Successful `204` responses resolve without a body. HTTP failures become `ApiError` values; Spring `ProblemDetail` payloads retain their error code and validated field errors. Network failures and unsuccessful consumption/parsing of an otherwise successful response body produce typed errors with status `0`, so a registration whose outcome is uncertain can offer sign-in recovery rather than assuming the account was not created.

The shared HTTP client exposes a plain client for anonymous operations and an identity-bound client for authenticated domain operations. The identity-bound client rejects a successful response when the originating tab session has since changed, and it distinguishes initial-401 recovery from terminal replay-401 notification. The IAM coordinator keeps access JWTs and the stable session ID in tab memory, and restores the shared HttpOnly-cookie session on first authenticated use, including after reload. It renews only after a documented **pre-dispatch** bearer `401`, under a same-origin Web Lock that also covers CSRF bootstrap and cookie-backed mutation. A repeatable operation can be replayed once after confirmed renewal; another pre-dispatch `401` ends recovery, while an application-generated `401` is propagated without invalidating a valid bearer. A transport or gateway failure after any submitted cookie-backed mutation can leave the browser cookie state uncertain; the coordinator invalidates its in-memory bearer and never automatically retransmits a potentially committed refresh. JWT expiration timestamps are advisory because the backend evaluates validity using its own clock. Browser tabs exchange only non-secret session-context notifications; a tab bound to another session suspends authenticated work rather than silently adopting the shared cookie. Explicit reload restores the browser's current cookie context. The backend owns durable rotation, replay revocation and session-bound refresh/logout, while the browser owns coordination and presentation. The [security architecture](../stack/security.md#api-boundaries-and-client-behavior) describes the HTTP lifecycle and its remaining token-staleness limit.

The client is independent of the page that invokes it: callers supply an API path and receive a typed result or error. The presentation-independent `core/identity/` adapter owns the typed IAM endpoints and session/locking coordination. During local development, `next.config.ts` rewrites same-origin `/api/:path*` requests to the Spring backend at `BACKEND_URL`. Ordinary Next.js builds retain standalone-server output. An explicit production export mode generates static HTML and assets for the Nginx-only frontend image; Nginx serves the static export over HTTPS and forwards production `/api/*` calls to Spring without altering the path or application cookies. It enforces a configured public hostname, rejects forged forwarding headers and keeps API responses uncached. Spring interprets the trusted forwarding values only under its production profile, which must be isolated behind the internal proxy network. This preserves the browser-facing URL and keeps business logic out of Next.js route handlers. Production Compose and database readiness are separate deployment responsibilities.

## Domain ownership and extension boundary

The application structure uses `core/<domain>/` outside the route tree for presentation-independent models, endpoint adapters and application operations. The `core/identity/` boundary owns authentication, refresh/logout orchestration, ephemeral bearer state and shared-cookie coordination. Its domain-first organization reflects backend business boundaries, while allowing several routes to consume the same domain-facing functionality without importing one another's page code.

The dependency direction is from routes and reusable UI toward domain-facing operations, and from domain HTTP adapters toward `lib/api/`. Transport-wide mechanics remain in `lib/api/`; domain-specific endpoint paths and data contracts belong to the domain adapter. A direct adapter is sufficient when an operation has no additional application behavior; a separate service represents real orchestration, not automatic delegation. UI state, navigation and display formatting remain presentation concerns. The Spring backend continues to evaluate authorization and financial semantics rather than relying on browser calculations.

The frontend's tests form a separate TypeScript/Vitest project under `tests/`, with API-client, component and accessibility coverage. Production code lives outside that project, and production TypeScript checking excludes test sources. Tests and application code share the frontend npm dependency installation, not a separate dependency graph.

The [visual design foundation](../design/sharewise-design-foundation.md) describes the appearance shared by the shells. The [frontend development guide](../development/frontend.md) contains commands, implementation conventions and validation practices; the [stack overview](../stack/overview.md) places the browser boundary in the wider deployment.
