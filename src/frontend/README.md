# ShareWise frontend

This directory contains the Next.js / React / TypeScript frontend for ShareWise. The UI is currently a starter page, not the implemented expense-sharing application. Next.js rewrites same-origin `/api/*` requests to the Spring backend during development; no server-side API route handlers are required.

## Develop and validate

From `src/frontend/`, install the locked dependencies and start the development server:

```sh
npm ci
npm run dev
```

The frontend is available at [http://localhost:3000](http://localhost:3000). To verify the development API rewrite, start the backend and request `http://localhost:3000/api/system/v1/health`. The Next.js server forwards `/api/*` to `BACKEND_URL` (default: `http://localhost:8080`).

Run the frontend checks before submitting changes:

```sh
npm run lint
npm run build
```

The application uses system fonts defined in `app/globals.css`, not `next/font/google`, so a production build does not fetch fonts from Google. For the supported toolchain, container networking, offline development and backend commands, see the [development guide](../../docs/development.md).
