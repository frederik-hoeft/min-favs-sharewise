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

The tests are a distinct Vitest/TypeScript project at `tests/`; the production TypeScript project excludes them. Both projects use the frontend root's npm dependencies, with no second lockfile. CI runs the same checks. `.npmrc` enforces the approved npm installation-script policy. The ordinary Next.js build produces standalone-server output. A separate `npm run test:static-export` probe compiles the same routes with static output, serves the resulting HTML and assets from a file-only HTTP server, and removes the generated `.next-export-probe/` directory. The probe omits the development-only `/api/*` rewrite without changing the normal build. It refuses to overwrite a pre-existing probe directory; a production export and Nginx delivery remain separate deployment work.

## Implementation boundaries

Route segments own pages, layouts, page-specific interaction state and private `_components/` compositions. Reusable presentation components reside in `components/<responsibility>/`, while `lib/` owns cross-cutting infrastructure such as the Mantine theme and HTTP transport. The reserved `core/<domain>/` structure is the future home of presentation-independent business-facing operations. These ownership relationships and their dependency direction are described in [frontend architecture](../architecture/frontend.md).

The UI uses Mantine controls and documented component customization APIs for common interaction behavior. Global theme tokens carry shared design decisions; an individual SCSS Module owns local composition. Sass imports resolve through the frontend `@/` alias, for example `@use "@/styles/breakpoints"`. Named breakpoints serve responsive layouts, and sizing uses `rem` except for simple borders. Document-wide CSS does not serve as a workaround for page- or component-specific presentation.

The checked-in [Mantine reference](../../.agents/references/frontend-mantine-llms-full.txt) describes component and Styles APIs for the installed library. Next.js's version-specific documentation is available in `node_modules/next/dist/docs/`, alongside the agent-specific notes in [`src/frontend/AGENTS.md`](../../src/frontend/AGENTS.md). Phosphor icons use the `@phosphor-icons/react/ssr` entry point in Server Components and `@phosphor-icons/react` in Client Components. Mantine's component slots accommodate leading and trailing icons; icon-only actions have accessible names.

## API integration

Browser API calls go through `@/lib/api`. `apiClient` exposes typed HTTP methods, normalizes both `/groups/v1/...` and `/api/groups/v1/...` beneath `/api`, and uses same-origin credentials. It returns decoded response bodies and raises `ApiError` for HTTP or network failures. Error details include HTTP status, a machine-readable code and any validation field errors carried by Spring's `ProblemDetail` response. Empty `204` responses resolve to `undefined`.

`createApiClient` accepts a custom `fetch` implementation and request/unauthorized hooks. The hooks support future credential attachment and at most one authorized retry after a `401`; no authentication flow is currently installed. Endpoint-specific paths and DTO mappings belong to domain adapters rather than to the transport client or presentation components. The API boundary and its development/runtime routing are described in [frontend architecture](../architecture/frontend.md).

## Test coverage and browser behavior

All frontend tests live in `tests/`, including tests of `lib/` and future domain modules. Vitest and React Testing Library cover observable component behavior, navigation and theme state. The API client is tested with an injected fetch implementation, so its success, `ProblemDetail`, network failure and bounded-retry cases run without a backend server or an additional Node test runner.

Browser-level appearance remains a distinct verification concern. jsdom and component accessibility checks do not establish responsive geometry, computed contrast, reduced-motion behavior or the full focus behavior of hydrated menus and Drawers. Visual changes are consequently checked in a browser across representative viewport widths and light, dark and system preferences. The public financial previews are static illustrations and have no dependency on API fixtures.

The [repository development guide](../development.md) covers backend startup, containers and offline dependency handoffs.
