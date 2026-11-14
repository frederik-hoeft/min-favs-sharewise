# ShareWise frontend

This directory contains the Next.js / React / TypeScript frontend for ShareWise. The homepage is currently a **Phase B static design candidate**, not an approved design or the implemented expense-sharing application. The server-side `/api/greetings` route forwards requests to the Spring Boot `/greeting` example endpoint.

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
npm exec --offline -- vitest run
npm run build
```

The frontend component and accessibility smoke tests run with Vitest, React Testing Library and axe-core. The tests require no backend; jsdom does not provide meaningful color-contrast or responsive-layout verification, so those remain browser-level checks. The checked-in [Mantine component reference](../../.agents/references/frontend-mantine-llms-full.txt) and [frontend agent instructions](AGENTS.md) document the component/styling conventions.

The frontend uses browser/system fonts, not `next/font/google`, so the production build does not fetch fonts from Google. The current landing page explores the candidate visual direction documented in [Quiet clarity](../../docs/design/sharewise-simplistic-candidate.md); it is illustrative only, pending Phase B human review. Mantine owns standard component styles and their shared color scheme; the root `app/providers.tsx` installs the theme and semantic light/dark roles from `lib/theme.ts`. `ColorSchemeScript` initializes light/dark/system selection before hydration.

Use adjacent `*.module.scss` files for local layout and composition, consuming Mantine CSS variables for shared tokens. `app/globals.scss` is reserved for document-level rules; component defaults and cross-app variants belong in the Mantine theme, not scattered global overrides. No Tailwind or external font service is required.

The current `output: "standalone"` and server-side greeting proxy remain temporary starter behavior. The intended static export is a separate deployment task; `cacheComponents`/partial prefetching are intentionally not enabled because PPR is incompatible with static export.

npm enforces `strict-allow-scripts=true` from `.npmrc`. The project's `allowScripts` policy explicitly blocks the optional `unrs-resolver` and `@parcel/watcher` installation helpers; all newly encountered dependency install scripts fail the install until reviewed. Do not blanket-approve scripts. This policy applies to dependency installation, not explicitly invoked `npm run` tasks. For the supported toolchain, container networking, offline development and backend commands, see the [development guide](../../docs/development.md).
