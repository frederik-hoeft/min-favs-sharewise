# Frontend development guide

This guide is for contributors adding or changing ShareWise frontend code. The [frontend architecture](../architecture/frontend.md) explains ownership and data flow; the [visual design foundation](../design/sharewise-design-foundation.md) describes the shared appearance. This document covers the choices and checks that matter while implementing a feature.

## Start and validate

Use Node.js 24 and npm with the checked-in lockfile. From `src/frontend/`:

```bash
npm ci
npm run dev
```

The development server runs at `http://localhost:3000`. Run the complete frontend check set before proposing a patch:

```bash
npm run lint
npm run typecheck
npm run test
npm run test:typecheck
npm run build
```

`npm run test` runs the dedicated `tests/` Vitest project, including unit, component and accessibility smoke tests. Its TypeScript project is checked separately, and the production TypeScript project excludes test sources. Tests share the root npm dependency installation; `tests/` is a separate project, not a second lockfile or dependency install. CI runs these commands from the frontend root. The npm script allowlist is enforced by `.npmrc`; do not disable it to work around missing or unreviewed installation scripts.

A passing production build does not demonstrate a static export: the current configuration uses standalone Next.js output and a development API rewrite. The Nginx/static-export deployment remains a separate task. Avoid introducing runtime server dependencies into client-facing features, and validate export compatibility when a change could affect it.

## Place code with its owner

Next.js route segments own their pages, layouts and private `_components/` compositions. The public `(public)` group and workspace `(app)` group each configure their own AppShell. A new workspace page belongs under `app/(app)/app/`; its URL path is independent of the parenthesized route-group name. Shared visual components live in `components/<responsibility>/`, not in another page's private directory. Move a component to shared UI when its responsibility is genuinely reusable, not just because two elements look similar.

Future business code belongs in domain-oriented `core/<domain>/` modules outside `app/`. Keep presentation state and formatting in the UI, use typed operations for domain-facing calls, and avoid creating services that only forward calls. Cross-domain transport belongs in `lib/api/`; endpoint-specific adapters and DTOs belong to the domain that owns them. The backend remains the source of truth for financial rules and authorization.

Keep reusable styles in Mantine's theme and semantic CSS variables. Use Mantine components and documented theme/Styles APIs for standard controls before adding custom CSS. Put component and page composition in adjacent SCSS Modules, use `@use "@/styles/breakpoints"` for named responsive mixins, and use `rem` for sizing except simple borders. The global stylesheet is for document-wide behavior, not a place to patch page styling or Mantine implementation selectors.

The complete checked-in [Mantine component reference](../../.agents/references/frontend-mantine-llms-full.txt) is searchable by component and Styles API section. Use the installed types and Next.js documentation in `node_modules/next/dist/docs/` for the actual package versions. The latest Next.js agent guidance in [`src/frontend/AGENTS.md`](../../src/frontend/AGENTS.md) also applies. Phosphor icons come from `@phosphor-icons/react/ssr` in Server Components and `@phosphor-icons/react` in Client Components; use Mantine slots for leading/trailing icons, with accessible names for icon-only actions.

## Work across the browser/API boundary

The P1.4 client is exported from `@/lib/api`. `apiClient` sends relative `/api/*` requests using `fetch` and same-origin credentials; `createApiClient` allows request hooks and an injected fetch implementation for integration tests. It serializes JSON bodies, parses JSON responses and surfaces non-success responses as `ApiError`, including machine-readable `code` and optional `fieldErrors` from Spring's `ProblemDetail`. A `204` response resolves to `undefined`; network failures produce a typed error with status `0`.

A caller may use a path such as `/groups/v1/...` or `/api/groups/v1/...`; the client normalizes both beneath `/api`. Domain adapters should own their endpoint paths and contract-specific mapping, leaving components to consume typed data. The shared client does not implement authentication, token refresh, CSRF issuance or authorization. Its hooks are extension points for the future IAM slice, not guarantees that those flows already exist.

In development, Next.js forwards `/api/:path*` to `${BACKEND_URL}/api/:path*` (default `http://localhost:8080`). `BACKEND_URL` is resolved by the Next.js server, so separate containers must use a backend address reachable on their shared network. The browser always calls the frontend's origin. Production will route `/api/*` at Nginx instead; do not add Next.js API route handlers or call the backend directly from client-side code.

## Test boundaries, not implementation trivia

Put **all frontend tests** under `src/frontend/tests/`, including tests for modules in `lib/` and future domain modules. Mirror the ownership of the unit under test where useful (`tests/api/`, route/component suites), and use Vitest, React Testing Library, and test-local fakes. The API-client tests inject a fetch implementation and require no running server. Test externally visible behavior, status/error parsing, and the bounded retry hook without a parallel Node test runner.

Use component tests for navigation, theme state, accessibility and visible interaction. jsdom/axe cannot validate viewport geometry, computed contrast or real browser focus behavior reliably. Review responsive layouts and light/dark states in a browser for visual changes, and respect system theme and reduced-motion settings. Keep the public financial previews static and do not mistake their illustrative data for API contracts.

For container setup, backend startup and offline dependency handoffs, see the [repository development environment guide](../development.md).
