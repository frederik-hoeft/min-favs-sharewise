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
npm run build
```

The application uses system fonts defined in `app/globals.css`, not `next/font/google`, so a production build does not fetch fonts from Google. For the supported toolchain, container networking, offline development and backend commands, see the [development guide](../../docs/development.md).
