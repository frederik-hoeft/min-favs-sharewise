# ShareWise frontend

The ShareWise frontend is a Next.js App Router / React / TypeScript application using Mantine, SCSS Modules and Phosphor icons. The public landing page illustrates the product's shared-expense model; `/app` and its dummy routes demonstrate the future workspace navigation. These examples are static and do not provide authentication or business operations.

## Run locally

From `src/frontend/`:

```bash
npm ci
npm run dev
```

Open `http://localhost:3000`. The development server forwards same-origin `/api/*` requests to Spring at `BACKEND_URL` (default `http://localhost:8080`). Start the backend separately to exercise its health endpoint at `/api/system/v1/health`.

Before submitting changes, run:

```bash
npm run lint
npm run typecheck
npm run test
npm run test:typecheck
npm run build
```

Tests live in the separate `tests/` project and run through the root npm scripts. The shared browser API client is in `lib/api/`; it does not depend on the backend for its unit tests.

## Where to go next

- [Frontend architecture](../../docs/architecture/frontend.md): route ownership, shared UI, transport boundary and future domain modules.
- [Frontend development guide](../../docs/development/frontend.md): component placement, styling, API use, testing and implementation caveats.
- [Quiet clarity design](../../docs/design/sharewise-design-foundation.md): visual language and centralized Mantine theme.
- [Repository development environment](../../docs/development.md): containers, toolchain, backend and offline handoff.

The current Next.js build runs as a standalone server; static export behind Nginx remains a separate deployment task. For library-specific guidance, see [frontend agent notes](AGENTS.md) and the checked-in [Mantine reference](../../.agents/references/frontend-mantine-llms-full.txt).
