# Frontend architecture

ShareWise's frontend has two independent responsibilities: it presents the product and workspace through Next.js routes, and it provides a browser-side boundary to the Spring API. Route structure determines where an experience is presented; it does not define business ownership. The backend remains authoritative for identities, group membership, expenses, balances, settlements and financial history.

The implemented frontend is a foundation for those workflows. The public site uses static financial illustrations, and the application workspace contains demonstration routes rather than protected business data. The standalone authentication pages call the implemented production IAM registration/login/CSRF endpoints through the shared API client when the backend is configured with IAM signing trust. Automated browser-side identity tests use an injected mock transport; the real HTTPS browser flow is a separate acceptance gate. A shared browser HTTP client and same-origin development API forwarding provide the transport boundary.

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
        ├── dummy1/page.tsx    /app/dummy1
        ├── dummy2/page.tsx    /app/dummy2
        ├── dummy3/            Nested breadcrumb demo and ancestor routes
        └── _components/       Workspace navigation and shell behavior
```

The public shell provides an inset, fixed header, in-page navigation and a footer. Its expense, settlement and history previews are presentational illustrations backed by static example data. The workspace shell uses Mantine's `AppShell` with an edge-attached sidebar and header; the primary content is centered in the space left by navigation. The sidebar is expanded on wide screens, becomes an icon rail at intermediate widths and is replaced by a Drawer on mobile. Breadcrumbs reflect the workspace route: they retain the root and current location, and when the full hierarchy exceeds the available width, a navigable menu contains the omitted intermediate locations. The current location stays on one line within the fixed-height header; if its label is truncated, a popover exposes the full text on mouse, touch or keyboard activation. The account menu currently exposes appearance selection. All three experiences share the same theme without coupling their navigation or layout state.

UI ownership follows the smallest meaningful boundary. Route-local `_components/` folders hold compositions whose semantics belong to a particular page or shell; Next.js excludes these folders from routing, but they are not TypeScript-private. `components/` holds application-wide presentation responsibilities, such as branding, participant identity and appearance controls. Each component's SCSS Module owns its local styling; shared Sass utilities provide common responsive definitions. The global stylesheet covers document-wide concerns only.

This distinction also defines the React boundary: layouts and pages can remain Server Components, while interactive menus, theme controls and responsive navigation use Client Components. Domain-independent presentation components do not become domain services merely because several pages use them.

## Browser API boundary

`lib/api/` owns HTTP transport behavior for browser callers. The client normalizes paths beneath `/api`, uses same-origin credentials and uncached requests by default, serializes JSON request bodies, and decodes JSON or text responses. Successful `204` responses resolve without a body. HTTP failures become `ApiError` values; Spring `ProblemDetail` payloads retain their error code and validated field errors. Network failures and unsuccessful consumption/parsing of an otherwise successful response body produce typed errors with status `0`, so a registration whose outcome is uncertain can offer sign-in recovery rather than assuming the account was not created.

The shared HTTP client accepts request and unauthorized-response hooks. The IAM coordinator keeps access JWTs in tab memory, restores a refresh-cookie session on first authenticated use, and renews only after a documented pre-dispatch bearer `401`. It holds the same-origin Web Lock while bootstrapping CSRF and submitting cookie-backed mutations. One repeatable operation may be replayed once; a second `401` or an uncertain refresh outcome terminates recovery. Expiration timestamps are advisory because the backend evaluates JWT validity using its own clock. Browser tabs exchange only non-secret session-context notifications; a tab bound to another session suspends its authenticated operations rather than adopting the cookie silently. The `/refresh` and `/logout` HTTP endpoints and the `sessionId` login response are provided by the subsequent IAM HTTP integration phase, so this client contract is exercised with mocked transport until that phase is accepted.

The client is independent of the page that invokes it: callers supply an API path and receive a typed result or error. The presentation-independent `core/identity/` adapter owns the typed IAM endpoints and session/locking coordination. During local development, `next.config.ts` rewrites same-origin `/api/:path*` requests to the Spring backend at `BACKEND_URL`. Ordinary Next.js builds retain standalone-server output. An explicit production export mode generates static HTML and assets for the Nginx-only frontend image; Nginx serves the static export over HTTPS and forwards production `/api/*` calls to Spring without altering the path or application cookies. It enforces a configured public hostname, rejects forged forwarding headers and keeps API responses uncached. Spring interprets the trusted forwarding values only under its production profile, which must be isolated behind the internal proxy network. This preserves the browser-facing URL and keeps business logic out of Next.js route handlers. Production Compose and database readiness are separate deployment responsibilities.

## Domain ownership and extension boundary

The application structure uses `core/<domain>/` outside the route tree for presentation-independent models, endpoint adapters and application operations. The `core/identity/` boundary owns authentication, refresh/logout orchestration, ephemeral bearer state and shared-cookie coordination. Its domain-first organization reflects backend business boundaries, while allowing several routes to consume the same domain-facing functionality without importing one another's page code.

The dependency direction is from routes and reusable UI toward domain-facing operations, and from domain HTTP adapters toward `lib/api/`. Transport-wide mechanics remain in `lib/api/`; domain-specific endpoint paths and data contracts belong to the domain adapter. A direct adapter is sufficient when an operation has no additional application behavior; a separate service represents real orchestration, not automatic delegation. UI state, navigation and display formatting remain presentation concerns. The Spring backend continues to evaluate authorization and financial semantics rather than relying on browser calculations.

The frontend's tests form a separate TypeScript/Vitest project under `tests/`, with API-client, component and accessibility coverage. Production code lives outside that project, and production TypeScript checking excludes test sources. Tests and application code share the frontend npm dependency installation, not a separate dependency graph.

The [visual design foundation](../design/sharewise-design-foundation.md) describes the appearance shared by the shells. The [frontend development guide](../development/frontend.md) contains commands, implementation conventions and validation practices; the [stack overview](../stack/overview.md) places the browser boundary in the wider deployment.
