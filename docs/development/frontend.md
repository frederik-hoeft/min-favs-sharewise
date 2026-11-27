# Frontend development

ShareWise's frontend is built with Next.js App Router, React, TypeScript, Mantine, SCSS Modules and Phosphor icons. This guide describes the development environment and the conventions that make feature work compatible with the existing foundation. The [frontend architecture](../architecture/frontend.md) defines ownership and data flow; the [visual design foundation](../design/sharewise-design-foundation.md) defines the shared visual language.

## Local execution and verification

Node.js 24 and npm provide the frontend toolchain. The checked-in lockfile establishes the dependency graph. From `src/frontend/`, a clean install and development server start with:

```bash
npm ci
npm run dev
```

The frontend is served at `http://localhost:3000`. Development `/api/*` traffic is forwarded to Spring, whose address is configured by `BACKEND_URL` (default `http://localhost:8080`). When the frontend and backend occupy different containers, that address resolves from the Next.js process's network, not the browser's network.

The frontend check set is:

```bash
npm run lint
npm run typecheck
npm run test
npm run test:typecheck
npm run build
npm run build:static
npm run test:static-export
```

The tests are a distinct Vitest/TypeScript project at `tests/`; the production TypeScript project excludes them. Both projects use the frontend root's npm dependencies, with no second lockfile. CI runs lint, application typecheck and test typecheck in the `pre-build` stage before production compilation. Backend and frontend builds then gate the `test` stage, where Vitest and the isolated static-export probe run as separate jobs. Vitest publishes a JUnit report. `.npmrc` enforces the approved npm installation-script policy. The ordinary `npm run build` produces standalone-server output. `npm run build:static` sets `SHAREWISE_STATIC_EXPORT=1`, exports static HTML and assets to `.next-export/`, and omits the development-only `/api/*` rewrite. The frontend Dockerfile uses that same export mode for its Nginx-only runtime. `npm run test:static-export` creates a disposable frontend workspace using the installed dependencies, verifies exported routes and assets through a file-only HTTP server, and deletes the workspace without changing the normal `.next/` output. The Nginx production image terminates TLS and proxies same-origin `/api/*` calls; certificate mounts, the private backend network and runtime startup orchestration are supplied by the production Compose work.

## Implementation boundaries

Route segments own pages, layouts, page-specific interaction state and private `_components/` compositions. Reusable presentation components reside in `components/<responsibility>/`, while `lib/` owns cross-cutting infrastructure such as the Mantine theme and HTTP transport. The `core/<domain>/` structure owns presentation-independent business-facing operations, including the implemented `core/identity/` authentication adapter and coordination. These ownership relationships and their dependency direction are described in [frontend architecture](../architecture/frontend.md).

The UI uses Mantine controls and documented component customization APIs for common interaction behavior. Global theme tokens carry shared design decisions; an individual SCSS Module owns local composition. Sass imports resolve through the frontend `@/` alias, for example `@use "@/styles/breakpoints"`. Named breakpoints serve responsive layouts, and sizing uses `rem` except for simple borders. Document-wide CSS does not serve as a workaround for page- or component-specific presentation.

The checked-in [Mantine reference](../../.agents/references/frontend-mantine-llms-full.txt) describes component and Styles APIs for the installed library. Next.js's version-specific documentation is available in `node_modules/next/dist/docs/`, alongside the agent-specific notes in [`src/frontend/AGENTS.md`](../../src/frontend/AGENTS.md). Phosphor icons use the `@phosphor-icons/react/ssr` entry point in Server Components and `@phosphor-icons/react` in Client Components. Mantine's component slots accommodate leading and trailing icons; icon-only actions have accessible names.

## API integration

Browser API calls go through `@/lib/api`. The exported plain `apiClient` is for public/anonymous operations (such as health), while workspace components obtain authenticated domain APIs from `useAuthenticatedApis()` in `@/app/identity-provider`. The unscoped `IdentityRuntime.api` belongs to identity infrastructure, not application components. `apiClient` exposes typed HTTP methods, normalizes both `/groups/v1/...` and `/api/groups/v1/...` beneath `/api`, and uses same-origin credentials. It returns decoded response bodies and raises `ApiError` for HTTP or network failures. Error details include HTTP status, a machine-readable code and any validation field errors carried by Spring's `ProblemDetail` response. Empty `204` responses resolve to `undefined`.

`createApiClient` accepts a custom `fetch` implementation and an ordered request-handler pipeline. Injected transports receive the validated absolute API URL; test doubles should verify the expected origin before matching its path and query. The transport owns replay and the shared logical-request budget; IAM owns primary bearer preparation and refresh, and `core/authorization/` handles supplementary challenges and session-local caches. See [distributed authorization](../architecture/distributed-authorization.md#challenge-driven-acquisition) for the protocol and recovery rules. Domain adapters receive an `ApiClient` rather than importing `apiClient`; workspace pages supply `useAuthenticatedApis().api` through a shared hook, as `useGroupsApi` does. Retain the session-bound facade across multi-step workflows: a stale facade rejects calls rather than adopting a replacement login, while same-session bearer rotation preserves it. Do not pass session identifiers, JWTs or affinity policies from application forms. IAM CSRF/login/register/refresh/logout use the cookie-backed path; the password command remains a bearer-authenticated operation with its specific terminal session-denial handling. Workspace tests render inside `IdentityProvider` and `RequireAuthentication` with a signed-in runtime over scripted `fetch`.

The shared API client gives each HTTP attempt, including its response body, a default 45-second timeout, configurable with `timeoutMs` (`0` disables it only for deliberately reviewed long-running requests). The timeout excludes authentication preparation and recovery; a replay gets its own timeout. A caller-provided `AbortSignal` cancels the logical request across preparation, dispatch and recovery, but does not abandon an already-submitted shared refresh, which must settle under its Web Lock. A protected response, including a structured failure, is processed only for its originating active session context, including after asynchronous body parsing. A late renewal cannot reactivate a terminally invalidated session or invalidate a replacement login. The cross-tab Web Lock has a separate 30-second acquisition deadline; once held, the lock remains in place until the authentication operation and any canceled HTTP request have settled. A registration failure after the POST begins has an uncertain outcome, for which the UI recommends signing in before retrying registration. A refresh response lost after submission also has an uncertain outcome: the coordinator invalidates the in-memory bearer without resending the possibly consumed refresh credential. A failed CSRF bootstrap does not imply that a refresh POST committed. See the [frontend architecture](../architecture/frontend.md#browser-api-boundary) and [security design](../stack/security.md#api-boundaries-and-client-behavior) for the recovery and cross-tab contracts.

## Authorization diagnostics

When running `npm run dev`, the root runtime exposes detached, read-only authorization metadata in the browser console. Use the same interface in the explicit disposable browser-smoke build:

```javascript
console.table(sharewiseDebug.authorization.credentials());
console.table(sharewiseDebug.authorization.requirements());
console.table(sharewiseDebug.authorization.snapshot().inFlight);
```

The credential rows show the opaque key, descriptor, generation, expiry and usability. Requirement rows show the bucket, requirement ID, projection policy and referenced credential key. The snapshots contain no JWTs and do not initialize caches, evict entries or alter their order. Expired entries remain visible until normal lazy eviction, and an empty requirement marker has no credential reference. Resource identifiers and query projections can still contain development data; do not export them indiscriminately. The caches survive client-side page changes and same-session renewal; logout, lifetime replacement and document reload discard them. Ordinary production builds do not register the global.

For interpreting diagnostic counts, the default credential cache holds up to 128 credentials and 32 simultaneous acquisition flights. The requirement cache holds up to 32 request buckets with eight requirement IDs per bucket and eight learned projections per requirement. These are resource bounds, not authorization rules. See [cache validity and lifecycle](../architecture/distributed-authorization.md#cache-validity-and-lifecycle) for eviction and recovery behavior.

## Test coverage and browser behavior

All frontend tests live in `tests/`, including tests of `lib/` and domain modules. Vitest and React Testing Library cover observable component behavior, navigation and theme state. The API client is tested with an injected fetch implementation, so its success, `ProblemDetail`, network failure and bounded-retry cases run without a backend server or an additional Node test runner.

For browser verification, serve the frontend and backend through an HTTPS same-origin proxy so the `Secure`, host-only, `SameSite=Strict` authentication cookies and cross-tab Web Locks operate under their actual browser constraints. Node/MockMvc simulations do not establish browser cookie storage or Web Locks behavior.

Browser-level appearance remains a distinct verification concern. jsdom and component accessibility checks do not establish responsive geometry, computed contrast, reduced-motion behavior or the full focus behavior of hydrated menus and Drawers. Visual changes are consequently checked in a browser across representative viewport widths and light, dark and system preferences. The workspace route tree (`/app/**`, including `/app`) requires browser-side authentication and preserves the requested route with a validated `next` parameter when redirecting to login. Terminal reauthentication navigates to login; a retryable restoration failure offers an explicit retry, and a changed cross-tab session asks for reload instead of adopting another account silently. The Account page exercises real protected API operations: verify profile set/clear, password-change failure and success feedback, current/other-session behavior, and uncertain-result recovery against the HTTPS deployment. Browser route guards cannot make static-exported assets confidential; the backend remains the enforcement boundary for protected data and operations. The public financial previews are static illustrations and have no dependency on API fixtures.

For Group workflows, [provision local test accounts](../development.md#provision-local-group-users) and verify the responsive workspace, member administration and invitation creation/acceptance with distinct accounts. Exercise sign-in and registration detours, preview before explicit join, skip-link navigation without losing the invitation, unavailable links, and cancellation or session replacement while requests are pending. Verify both the desktop aside and narrow-screen Drawer, including focus restoration and long identifiers. Keep invitation capabilities out of return queries, storage and ordinary logs; the [invitation continuation architecture](../architecture/frontend.md#invitation-acceptance) describes the intended browser lifetime.

The [repository development guide](../development.md) covers backend startup, containers and offline dependency handoffs.

The deployed authorization suite under `tests/deployed/` is separate from the Unit/component suite. Run it through [disposable Compose acceptance](../../deploy/README.md#disposable-end-to-end-acceptance), which supplies the real HTTPS origin, IAM/Group services and isolated consumer fixtures. Use `--browser` for the interactive fixture and browser-specific behavior; Node tests do not establish browser cookie or Web Lock enforcement.
