<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## ShareWise frontend conventions

Follow the [frontend ownership and module organization](../../docs/architecture/frontend.md) for the separation between route-local presentation, shared UI and future domain-oriented services. Keep domain/application logic outside the Next.js route tree; add domain modules only for implemented workflows.

Use Mantine (`@mantine/core`, `@mantine/hooks`) for standard controls, color-scheme management and shared theme defaults. Its **checked-in API and component documentation** is at [`.agents/references/frontend-mantine-llms-full.txt`](../../.agents/references/frontend-mantine-llms-full.txt). This reference is large: search for the relevant component, hook or Styles API section and read only that section before implementing or overriding Mantine behavior. Use the installed package types as the final authority for the version actually present. The official [Mantine documentation](https://mantine.dev/) is a supplementary reference when online access is available.

Use adjacent `*.module.scss` files for page/component-specific composition and layouts. Reuse `styles/_breakpoints.scss` for Sass media queries and preserve rem-based sizing (except simple borders). Prefer Mantine theme tokens and CSS custom properties; reserve `app/globals.scss` for document-level styling. Do not introduce Tailwind, Bootstrap, a parallel styling system, or custom implementations of existing Mantine controls. The selected Quiet clarity design is defined in `docs/design/sharewise-design-foundation.md`; keep shared brand choices centralized in `lib/theme.ts` rather than scattering them across pages.

The frontend test harness uses Vitest, React Testing Library, user-event and axe-core. The tests are an isolated package/project at `tests/`, with their own Vitest configuration and TypeScript project; do not colocate test files with production routes or components. Run `npm run test` and `npm run test:typecheck` from `src/frontend/`. jsdom-based axe checks cannot accurately verify color contrast or geometry; these still require a real-browser review. Keep the test harness independent of the running backend and avoid network-dependent assets in the landing page.
