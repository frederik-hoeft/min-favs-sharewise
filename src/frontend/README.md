# ShareWise frontend

This directory contains the Next.js / React / TypeScript frontend for ShareWise. The UI is currently a starter page, not the implemented expense-sharing application. The server-side `/api/greetings` route forwards requests to the Spring Boot `/greeting` example endpoint.

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
npm run build
```

The frontend uses browser/system fonts, not `next/font/google`, so the production build does not fetch fonts from Google. The current page is a temporary starter, not the P2.0e landing-page design. CSS Modules provide locally scoped styling while the planned Mantine/SCSS foundation awaits a refreshed dependency lockfile and offline handoff.

npm enforces `strict-allow-scripts=true` from `.npmrc`. The project's `allowScripts` policy explicitly blocks the optional `unrs-resolver` postinstall helper; all newly encountered dependency install scripts fail the install until reviewed. Do not blanket-approve scripts. This policy applies to dependency installation, not explicitly invoked `npm run` tasks. For the supported toolchain, container networking, offline development and backend commands, see the [development guide](../../docs/development.md).
