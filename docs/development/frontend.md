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
npm run test:static-export
```

The tests are a distinct Vitest/TypeScript project at `tests/`; the production TypeScript project excludes them. Both projects use the frontend root's npm dependencies, with no second lockfile. CI runs lint, application typecheck and test typecheck in the `pre-build` stage before production compilation. Backend and frontend builds then gate the `test` stage, where Vitest and the isolated static-export probe run as separate jobs. Vitest publishes a JUnit report. `.npmrc` enforces the approved npm installation-script policy. The ordinary `npm run build` produces standalone-server output. `npm run build:static` sets `SHAREWISE_STATIC_EXPORT=1`, exports static HTML and assets to `.next-export/`, and omits the development-only `/api/*` rewrite. The frontend Dockerfile uses that same export mode for its Nginx-only runtime. `npm run test:static-export` creates a disposable frontend workspace using the installed dependencies, verifies exported routes and assets through a file-only HTTP server, and deletes the workspace without changing the normal `.next/` output. The Nginx production image terminates TLS and proxies same-origin `/api/*` calls; certificate mounts, the private backend network and runtime startup orchestration are supplied by the production Compose work.

## Implementation boundaries

Route segments own pages, layouts, page-specific interaction state and private `_components/` compositions. Reusable presentation components reside in `components/<responsibility>/`, while `lib/` owns cross-cutting infrastructure such as the Mantine theme and HTTP transport. The `core/<domain>/` structure owns presentation-independent business-facing operations, including the implemented `core/identity/` authentication adapter and coordination. These ownership relationships and their dependency direction are described in [frontend architecture](../architecture/frontend.md).

The UI uses Mantine controls and documented component customization APIs for common interaction behavior. Global theme tokens carry shared design decisions; an individual SCSS Module owns local composition. Sass imports resolve through the frontend `@/` alias, for example `@use "@/styles/breakpoints"`. Named breakpoints serve responsive layouts, and sizing uses `rem` except for simple borders. Document-wide CSS does not serve as a workaround for page- or component-specific presentation.

The checked-in [Mantine reference](../../.agents/references/frontend-mantine-llms-full.txt) describes component and Styles APIs for the installed library. Next.js's version-specific documentation is available in `node_modules/next/dist/docs/`, alongside the agent-specific notes in [`src/frontend/AGENTS.md`](../../src/frontend/AGENTS.md). Phosphor icons use the `@phosphor-icons/react/ssr` entry point in Server Components and `@phosphor-icons/react` in Client Components. Mantine's component slots accommodate leading and trailing icons; icon-only actions have accessible names.

## API integration

Browser API calls go through `@/lib/api`. `apiClient` exposes typed HTTP methods, normalizes both `/groups/v1/...` and `/api/groups/v1/...` beneath `/api`, and uses same-origin credentials. It returns decoded response bodies and raises `ApiError` for HTTP or network failures. Error details include HTTP status, a machine-readable code and any validation field errors carried by Spring's `ProblemDetail` response. Empty `204` responses resolve to `undefined`.

`createApiClient` accepts a custom `fetch` implementation and request/unauthorized hooks. `core/identity/` installs the current in-memory access bearer and invalidates it on expiry or a matching `401`; there is no automatic renewal or retry in P2.1a. The client supports at most one explicitly authorized retry when a future flow supplies it. Endpoint-specific paths and DTO mappings belong to domain adapters rather than to the transport client or presentation components. The IAM adapter bootstraps CSRF inside an exclusive cross-tab Web Lock before each register/login mutation, so a previously invalidated CSRF cookie is never reused. An unavailable lock fails closed rather than performing an unlocked authentication mutation. The API boundary and its development/runtime routing are described in [frontend architecture](../architecture/frontend.md).

The shared API client has a default 45-second request deadline, configurable per call with `timeoutMs` (`0` disables it only for deliberately reviewed long-running requests). The cross-tab Web Lock has a separate 30-second acquisition deadline; once held, the lock remains in place until the authentication operation and any canceled HTTP request have settled. A registration failure after the POST begins is an uncertain outcome, for which the UI recommends signing in before retrying registration. P2.1b extends this boundary with clock-skew-tolerant and bounded renewal; see the [Phase 2 backlog](../internal/tasks/backlog.md#p21---iam-vertical-slices).

## Test coverage and browser behavior

All frontend tests live in `tests/`, including tests of `lib/` and future domain modules. Vitest and React Testing Library cover observable component behavior, navigation and theme state. The API client is tested with an injected fetch implementation, so its success, `ProblemDetail`, network failure and bounded-retry cases run without a backend server or an additional Node test runner.

For live registration/login acceptance, follow the [P2.1a Phase G checklist](../internal/tasks/p2.1a-registration-auth/acceptance.md). The browser must reach the backend through an HTTPS same-origin proxy: auth cookies are `Secure`, host-only and `SameSite=Strict`; Node/MockMvc simulations do not establish actual cookie storage or Web Locks behavior.

Browser-level appearance remains a distinct verification concern. jsdom and component accessibility checks do not establish responsive geometry, computed contrast, reduced-motion behavior or the full focus behavior of hydrated menus and Drawers. Visual changes are consequently checked in a browser across representative viewport widths and light, dark and system preferences. The public financial previews are static illustrations and have no dependency on API fixtures.

The [repository development guide](../development.md) covers backend startup, containers and offline dependency handoffs.
