# P1.4 - Implementation plan: API conventions, configuration, test conventions and frontend API foundation

**Status:** Approved plan alongside the [P1.4 specification](specification.md); Phases A–D implemented and verified, awaiting final human review. **Execution gate:** Each implementation phase requires human review and approval before the next phase begins, following the [AI development workflow](../../../development/ai-development-workflow.md).

## Baseline and prerequisites

The repository contains the Java 25 / Spring Boot 4.1.2 Maven backend (`src/backend/sharewise`), the operation-scoped DI foundation (`de.hsh.favs.sharewise.shared.infra.di`), and the Next.js 16.4.1 frontend (`src/frontend`). To keep the build strictly reproducible with the existing offline Maven and npm caches, P1.4 uses the already installed Spring Boot MVC (`ProblemDetail`, `@RestControllerAdvice`, `@ConfigurationProperties`, Jackson) and Next.js (`rewrites` in `next.config.ts`, native `fetch`) capabilities without modifying `pom.xml` or `package-lock.json`.

**Phase completion rule:** Check a task box only when its changes have been validated **and human-approved**. All Java code (including tests) must declare explicit variable types (`var` is forbidden).

## Phase A (P1.4b) - Shared operation result and test conventions

**Covers:** API-04, API-07; P1.4-T07 (relevant portions).

- [x] Add `Result<T, E>` (sealed interface with `Ok<T, E>` / `Err<T, E>` record variants and concise mapping/inspection helpers) in `de.hsh.favs.sharewise.shared` for expected application-level business outcomes.
- [x] Establish JUnit 5 tag conventions (`@Tag("component")` and `@Tag("integration")`) and configure Maven Surefire so default `./mvnw test` runs fast offline unit and component tests while database-dependent integration tests remain explicitly selectable.
- [x] Add unit tests for `Result<T, E>` invariants and verify existing DI tests continue to pass with explicit types.

**Gate A:** From `src/backend/sharewise`, `./mvnw test` passes offline and `git diff --check` is clean.

## Phase B (P1.4a) - Backend API error contract, boundary validation, configuration and health endpoint

**Covers:** API-01, API-02, API-03, API-04, API-05, API-06; P1.4-T01 through P1.4-T04.

- [x] Implement the shared API error contract (`ProblemDetail` builder utilities, stable `code` property, `FieldErrorDetail(String field, String code, String message)` list, and boundary validation exception types) and a global `@RestControllerAdvice` handling validation errors (`400`, `validation_failed`), unreadable/malformed payloads (`400`, `malformed_request`), method/media-type errors (`405`/`415`), and unexpected exceptions (`500`, `internal_error` without stack trace or class-name leakage).
- [x] Enable and bind fail-fast immutable `@ConfigurationProperties` records for core application settings, validating required values at startup.
- [x] Implement `GET /api/system/v1/health` (and align `/api/greetings` or greeting compatibility for the starter page) returning `200 OK` (`{"status":"UP"}`) when healthy and `503 Service Unavailable` (`{"status":"DOWN"}`) when a registered readiness probe fails.
- [x] Add MockMvc / component and Spring context tests covering `P1.4-T01` through `P1.4-T04` (invalid fields, malformed JSON, `Result.Err` mapping, `500` diagnostic suppression, fail-fast configuration properties, and health status transitions).

**Gate B:** From `src/backend/sharewise`, `./mvnw test` passes all new component and context tests offline; `git diff --check` is clean.

## Phase C (P1.4c) - Frontend `/api/*` rewrite and typed API client

**Covers:** API-08, API-09; P1.4-T05, P1.4-T06, P1.4-T07.

- [x] Configure the `/api/:path*` → `${BACKEND_URL}/api/:path*` rewrite in `src/frontend/next.config.ts` and remove `src/frontend/app/api/greetings/route.ts` so Next.js has no server-side API route handlers.
- [x] Implement the typed frontend API client in `src/frontend/lib/api/` (`apiClient`, `ApiError`, `ProblemDetailPayload`, `FieldError`, `credentials: 'same-origin'`, JSON handling, and header/`401` extension hooks for P2.3).
- [x] Update `src/frontend/app/greetings/page.tsx` to use the shared API client through the `/api/*` rewrite, and verify error/response handling.

**Gate C:** From `src/frontend`, `npm run lint` and `npm run build` succeed offline; no `src/frontend/app/api/` route files remain.

## Phase D - Documentation reconciliation and acceptance handoff

**Covers:** Steady-state documentation update and final verification.

- [x] Update `docs/development.md` and `docs/stack/overview.md` where needed to reflect the implemented `/api/*` rewrite, health check, error contract and test commands.
- [x] Run full backend `./mvnw clean verify` (or `./mvnw test`) and frontend `npm run lint && npm run build`, verify `git diff --check`, and record manual end-to-end verification steps for human acceptance.

**Gate D:** Human reviews the complete diff, runs/confirms the end-to-end check, and approves P1.4.
