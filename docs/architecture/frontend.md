# Frontend architecture

ShareWise is a browser-facing Next.js application with two distinct experiences: a public introduction to the product and a workspace for future business features. Both use the same design system, but they have different navigation and page composition needs. **Routes own presentation; domain modules will own business-facing application behavior.** This distinction keeps the UI free to evolve without making the URL structure determine the domain model.

The current application is a foundation, not a connected financial product. The landing page contains static examples, `/app` contains placeholder routes, and authentication and domain-specific workflows are not yet implemented. The browser HTTP client and development API forwarding are real infrastructure delivered by P1.4. The [backend architecture](../architecture.md) and [domain model](../data-model-design.md) define the authoritative business boundaries and financial rules.

## Routing and presentation

Next.js App Router supplies the document layout, route composition and server/client component boundaries. A common root layout installs Mantine's theme and color-scheme provider. Two route groups provide different shells:

- `app/(public)/` owns the landing page, its floating header, in-page navigation, footer and illustrative financial views.
- `app/(app)/app/` owns the `/app` URL subtree, with a docked Mantine AppShell, breadcrumbs, account menu, responsive sidebar and mobile navigation drawer. Its `/app/dummy1` and `/app/dummy2` pages are placeholders, not authenticated features.

The route-group names in parentheses do not appear in URLs. The additional `app/` directory inside `(app)` gives application routes their `/app` prefix. Each layout owns its navigation without a runtime switch between public and authenticated states. Shared brand and appearance controls remain independent of either shell.

The principal directories under `src/frontend/` reflect this ownership:

```text
app/                  Next.js routes, layouts, and route-local UI
components/           Reusable presentation components
lib/api/              Shared browser HTTP client and errors
lib/theme.ts          Shared Mantine theme
styles/               Shared Sass helpers
core/<domain>/        Future domain-oriented frontend modules (not yet present)
tests/                Dedicated Vitest and TypeScript test project
```

Presentation components stay close to their owner. A page's `_components/` directory contains compositions or controls that only make sense for that page or route subtree; Next.js excludes these private folders from route matching. `components/` holds stable, reusable UI responsibilities such as the brand, participant avatars and theme selection. These are organizational boundaries, not TypeScript access restrictions. CSS Modules stay adjacent to their components; `styles/` holds only reusable Sass utilities.

## Browser API boundary

A shared, dependency-free client in `lib/api/` centralizes HTTP mechanics. It sends relative `/api/*` requests with same-origin credentials, serializes JSON bodies and maps HTTP and network failures to typed `ApiError` values. RFC 9457 `ProblemDetail` responses retain machine-readable codes and optional validation field errors. Optional request and unauthorized-response hooks are extension points for later authentication work; **they do not yet provide a session or token lifecycle**.

During development, `next.config.ts` rewrites `/api/:path*` to the Spring backend at `BACKEND_URL`. The browser still requests the same origin. The production target is an exported static frontend served by Nginx with `/api/*` forwarded directly to Spring; that deployment is not implemented yet. The current Next.js build uses standalone server output, and its rewrite is a development/runtime concern, not part of a static export.

The shared client owns transport behavior, while future domain adapters will own endpoint paths, contract-specific types and mappings. Neither route components nor presentation helpers should implement another HTTP transport layer. The [frontend development guide](../development/frontend.md) explains how to use and test this boundary.

## Domain modules as features arrive

Future business-facing frontend modules belong outside the route tree, grouped by domain under `core/<domain>/`. A group module might contain its presentation-independent model, HTTP adapter and application operations. Only create layers that have a real responsibility: a read-only request does not need a pass-through application service, and a transport DTO needs a separate model only when the representations genuinely differ.

Dependency direction follows the responsibility boundaries: pages and shared UI consume domain-facing operations and models; domain adapters use `lib/api/` for shared transport; `core/` does not depend on React views, Next.js navigation, component styles or route-owned helpers. UI-specific formatting and interaction state remain with presentation. Business authorization, expense allocation, balances, settlement interpretation and financial history remain authoritative on the backend.

`lib/` hosts shared non-domain infrastructure such as the current HTTP client and Mantine configuration. It is not a place for unrelated business services. Tests live in the separately configured `tests/` project, including API-client tests and route/component tests; production directories contain no test files.

## Further reading

- [Visual design foundation](../design/sharewise-design-foundation.md) describes the shared UI language and Mantine theme ownership.
- [Frontend development guide](../development/frontend.md) explains contributor conventions, workflows and validation.
- [Technical stack overview](../stack/overview.md) puts the frontend in the wider ShareWise deployment and backend context.
