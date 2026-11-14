<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## ShareWise frontend conventions

Use Mantine (`@mantine/core`, `@mantine/hooks`) for standard controls, color-scheme management and shared theme defaults. Its **checked-in API and component documentation** is at [`.agents/references/frontend-mantine-llms-full.txt`](../../.agents/references/frontend-mantine-llms-full.txt). This reference is large: search for the relevant component, hook or Styles API section and read only that section before implementing or overriding Mantine behavior. Use the installed package types as the final authority for the version actually present. The official [Mantine documentation](https://mantine.dev/) is a supplementary reference when online access is available.

Use adjacent `*.module.scss` files for page/component-specific composition and layouts. Prefer Mantine theme tokens and CSS custom properties; reserve `app/globals.scss` for document-level styling. Do not introduce Tailwind, Bootstrap, a parallel styling system, or custom implementations of existing Mantine controls. Phase B determines the visual direction: do not embed arbitrary brand choices in the shared foundation.

The frontend test harness uses Vitest, React Testing Library, user-event and axe-core. Run `npm exec --offline -- vitest run` from `src/frontend/` for the test suite. jsdom-based axe checks cannot accurately verify color contrast or geometry; these still require a real-browser review. Keep the test harness independent of the running backend and avoid network-dependent assets in the landing page.
