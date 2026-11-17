# ShareWise frontend

This directory contains the Next.js / React / TypeScript frontend for ShareWise. The homepage demonstrates the **selected Quiet clarity visual foundation** with illustrative, nonfunctional financial previews. It is not the implemented expense-sharing application. The server-side `/api/greetings` route forwards requests to the Spring Boot `/greeting` example endpoint.

## Develop and validate

From `src/frontend/`, install the locked dependencies and start the development server:

```sh
npm ci
npm run dev
```

The frontend is available at [http://localhost:3000](http://localhost:3000). For the greeting endpoint to work, the backend must be running and accessible to the Next.js server at `BACKEND_URL` (default: `http://localhost:8080`).

Run the frontend checks before submitting changes:

```sh
npm run lint
npm run typecheck
npm run test
npm run test:typecheck
npm run build
```

The separate `tests/` package contains component, theme, and accessibility smoke tests (Vitest, React Testing Library, and axe-core). Its own `package.json` and TypeScript project share the locked dependencies installed by `npm ci` at the frontend root. The production TypeScript project excludes this test project. The tests require no backend; jsdom does not provide meaningful color-contrast or responsive-layout verification, so those remain browser-level checks. The checked-in [Mantine component reference](../../.agents/references/frontend-mantine-llms-full.txt) and [frontend agent instructions](AGENTS.md) document the component/styling conventions.

For directory ownership, route-local versus shared components, and the intended domain-first service organization, see the [frontend architecture guide](../../docs/architecture/frontend.md).

The frontend uses browser/system fonts, not `next/font/google`, so the production build does not fetch fonts from Google. Public navigation and footer live in the `(public)` route-group layout, with Mantine `AppShell` providing the fixed header and main-content offset. The root layout installs providers only, allowing future authenticated routes to configure their own AppShell and sidebar without coupling them to the marketing page. The landing page uses the selected [Quiet clarity design foundation](../../docs/design/sharewise-design-foundation.md); its finance illustrations remain static examples, not production workflows. Mantine owns standard component styles and their shared color scheme; Phosphor supplies UI icons (SSR entry point for Server Components); the root `app/providers.tsx` installs the theme and semantic light/dark roles from `lib/theme.ts`. `ColorSchemeScript` initializes light/dark/system selection before hydration.

Use adjacent `*.module.scss` files for local layout and composition, consuming Mantine CSS variables for shared tokens. Import `styles/_breakpoints.scss` for shared media queries; use `rem` for dimensions and typography, except simple borders. Mantine `Container` size `content` aligns main page sections. `app/globals.scss` is reserved for document-level rules; component defaults and cross-app variants belong in the Mantine theme, not scattered global overrides. No Tailwind or external font service is required.

The current `output: "standalone"` and server-side greeting proxy remain temporary starter behavior. The intended static export is a separate deployment task; `cacheComponents`/partial prefetching are intentionally not enabled because PPR is incompatible with static export.

npm enforces `strict-allow-scripts=true` from `.npmrc`. The project's `allowScripts` policy explicitly blocks the optional `unrs-resolver` and `@parcel/watcher` installation helpers; all newly encountered dependency install scripts fail the install until reviewed. Do not blanket-approve scripts. This policy applies to dependency installation, not explicitly invoked `npm run` tasks. For the supported toolchain, container networking, offline development and backend commands, see the [development guide](../../docs/development.md).
