<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## ShareWise frontend conventions

Read the [frontend architecture](../../docs/architecture/frontend.md) before changing module ownership, the [selected design foundation](../../docs/design/sharewise-design-foundation.md) before changing theme or reusable UI, and the [frontend development guide](../../docs/development/frontend.md) for everyday implementation, API client, test placement and validation conventions. Keep tests in the dedicated `tests/` project and reuse the shared `lib/api/` transport foundation.

Mantine's checked-in [component and Styles API reference](../../.agents/references/frontend-mantine-llms-full.txt) is the primary local UI reference. Search for the relevant component or theme API, then check the installed package types. Do not introduce competing UI libraries, per-page global overrides or duplicate HTTP clients. The [official Mantine docs](https://mantine.dev/) are supplementary when online. Use `@phosphor-icons/react/ssr` in Server Components and the root icon package in Client Components.

Follow [engineering quality](../../.agents/skills/engineering-quality/SKILL.md) for code changes and [documentation](../../.agents/skills/documentation/SKILL.md) for document edits. The architecture/design/development guides are authoritative; avoid copying their content into agent notes.
