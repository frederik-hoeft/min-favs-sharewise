# P1.4 - API conventions, configuration, test conventions and frontend API foundation

**Status:** Approved acceptance specification; implementation complete and verified, awaiting final human acceptance. **Backlog item:** [P1.4](../backlog.md#phase-1---modular-application-and-persistence-foundation). This task depends on the [application architecture](../../../architecture.md), [DI architecture](../../../architecture/dependency-injection.md), [preliminary security design](../../../stack/security.md), [P1.3b transaction specification](../p1.3-transactions/specification.md), and [P1.3c integration-test specification](../p1.3c-integration-tests/specification.md). The [development workflow](../../../development/ai-development-workflow.md) defines the specification, planning and review gates.

## Goal, scope and subtask decomposition

P1.4 establishes the cross-cutting HTTP, configuration, health-probe, test-execution and browser-transport conventions required before the first authenticated business slice in Phase 2. It defines how controllers validate requests and return typed errors, how environment configuration and readiness checks behave, how unit and integration test suites are selected and named, and how the browser communicates directly with the backend on the same origin without Next.js backend routes.

Because P1.4 spans backend HTTP infrastructure, build/test conventions and the Next.js frontend, it may be implemented and reviewed across three focused subtasks:

1. **P1.4a - Backend API error contract, request validation, configuration and health checks:** standardized error responses (`ProblemDetail` / RFC 9457), boundary validation handling, typed `@ConfigurationProperties` binding, and health/readiness endpoints.
2. **P1.4b - Unit and integration test conventions:** shared application-result conventions (`Result<T, E>`), JUnit/Maven test classification separating fast offline unit/web-slice tests from P1.3c database integration suites, and deterministic test configuration.
3. **P1.4c - Frontend `/api/*` development rewrite and API client foundation:** a single `/api/:path*` rewrite rule in `next.config.ts` (removing the starter's `app/api/` server-side route handler) so browser requests go directly to Spring Boot on the same origin without Next.js backend code, paired with a minimal typed browser API client.

P1.4 does **not** implement IAM authentication, JWT decoding, CSRF token issuance, Web Locks refresh coordination, domain modules, or the P8.1 production Next.js static export and Nginx deployment. Any addition of Maven or npm dependencies (for example `spring-boot-starter-validation` or `spring-boot-starter-actuator`) requires refreshing and revalidating the offline dependency handoff as required by the [backlog](../backlog.md#framework-projection-to-validate-in-design-reviews).

## Architectural solution and ownership

### 1. Backend API conventions, errors and validation (P1.4a)

Inbound REST controllers are thin transport adapters under their owning module (or `shared` for system probes) and delegate business execution through `OperationDispatcher`. Versioned domain routes follow the P0.2 convention `/api/<context>/v<version>/<resource>`.

**Typed error contract:** All `/api/**` error responses use Spring's RFC 9457 `ProblemDetail` (`application/problem+json`) handled by a centralized `@RestControllerAdvice`. To allow programmatic client handling without parsing human-readable messages, every error response includes a stable machine-readable `code` property alongside the standard `status`, `title` and `detail` fields, plus an optional `fieldErrors` list for input validation failures:

```json
{
  "type": "about:blank",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Request validation failed.",
  "code": "validation_failed",
  "fieldErrors": [
    { "field": "amount", "code": "must_be_positive", "message": "must be greater than 0" }
  ]
}
```

Controller boundary failures map deterministically:

| Failure condition | HTTP status | Error `code` | Guarantee |
| --- | --- | --- | --- |
| Bean/parameter validation or invalid field value | `400 Bad Request` | `validation_failed` | Includes stable `fieldErrors` entries (`field`, `code`, `message`). |
| Malformed JSON, unreadable body or type mismatch | `400 Bad Request` | `malformed_request` | Rejects unknown/unreadable payloads without leaking parser stack traces. |
| Unsupported HTTP method or media type | `405` / `415` | `method_not_allowed` / `unsupported_media_type` | Standard HTTP headers preserved. |
| Expected domain/application error (`Result.Err`) | Mapped by controller (`401`, `403`, `404`, `409`, `422`) | Domain-specific stable code | Explicitly mapped from the operation's typed error enum/sealed hierarchy. |
| Unhandled runtime or infrastructure exception | `500 Internal Server Error` | `internal_error` | Logs full server diagnostics; returns a generic client message with no internal class names, SQL or stack traces. |

**Request validation:** Inbound DTOs are immutable Java records validated at the HTTP boundary (via Jakarta Bean Validation where present and compact constructor/request checks) before dispatching an application operation. Transport validation checks syntax, presence and basic bounds; domain invariants remain in the application core.

**Configuration and health checks:** Application settings use immutable `@ConfigurationProperties` records bound at startup rather than scattered `@Value` annotations. Invalid or missing required properties fail startup fast. A lightweight health endpoint (`GET /api/system/v1/health`, or Spring Actuator mapped/aliased consistently when installed) reports liveness and database readiness (`200 OK` when healthy, `503 Service Unavailable` when a required check fails) without exposing sensitive environment properties.

### 2. Application result and test conventions (P1.4b)

**Shared operation result (`Result<T, E>`):** A small sealed interface in `de.hsh.favs.sharewise.shared` (with `Success<T, E>` and `Failure<T, E>` / `Err<T, E>` record variants) represents expected business outcomes returned by application services and inspected by `Transactions.execute(...)` orchestrators. `Optional<T>` remains preferred for simple absence-only lookups. `Result` is an in-process contract and is never serialized directly as an HTTP payload.

**Test classification and execution:** Building on [P1.3c](../p1.3c-integration-tests/specification.md), tests are organized so the default developer loop remains fast and offline:

| Suite | Selection convention | Allowed dependencies |
| --- | --- | --- |
| **Unit tests** | `*Tests` / `*Test` (default Maven `test` phase, no tag) | Plain JUnit 5, Mockito/fakes; no Spring context, database or network. |
| **Component / web-slice tests** | `@Tag("component")` or lightweight Spring context / `@WebMvcTest` | Spring DI or MockMvc with fake/mocked ports and dispatcher; no PostgreSQL required. |
| **Database integration tests** | `@Tag("integration")` (P1.3c rollback-operation and real-commit fixtures) | Real PostgreSQL and Spring context; fails or skips explicitly if PostgreSQL is unavailable, never falling back to H2. |

All Java test code follows the repository rule of explicit variable types (no `var`).

### 3. Frontend `/api/*` development rewrite and API client (P1.4c)

**Same-origin `/api/*` development forwarding (no Next.js API routes):** ShareWise does not use Next.js as a Backend-for-Frontend (BFF) and does not implement API route handlers in Next.js. In production (P8.1), Nginx serves the static export and routes `/api/*` directly to Spring Boot on the same origin. During local and container development before Nginx is introduced, `src/frontend/next.config.ts` configures a single wildcard rewrite rule:

```ts
async rewrites() {
  const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080';
  return [
    {
      source: '/api/:path*',
      destination: `${backendUrl}/api/:path*`,
    },
  ];
}
```

This eliminates the starter's `src/frontend/app/api/` route handler directory entirely while keeping all browser requests same-origin (`/api/...`), allowing Spring Boot's `HttpOnly` refresh cookies, CSRF cookies/headers, `Authorization` headers, request bodies and `ProblemDetail` error responses to pass directly between the browser and Spring Boot without custom Next.js server code.

**Frontend API client foundation:** A small, dependency-free `fetch` wrapper (for example under `src/frontend/lib/api/`) provides:

- Relative requests against `/api/...` with `credentials: 'same-origin'` and automatic JSON serialization/deserialization.
- Parsing of non-2xx `application/problem+json` responses into a typed `ApiError` carrying `status`, `code`, `detail`, and optional `fieldErrors`.
- Narrow extension hooks (such as optional request header injection and a `401` interception hook) so P2.3 can attach bearer tokens, CSRF tokens, and Web-Lock-coordinated token refresh without rewriting feature call sites.

## Requirements and invariants

- **API-01 - Versioned routes and ProblemDetail:** Every backend API endpoint under `/api/<context>/v<version>/...` returns RFC 9457 `application/problem+json` on error with a non-empty machine-readable `code` and HTTP status matching the payload's `status` field.
- **API-02 - No diagnostic leakage:** `500` and `400` responses never expose Java exception class names, stack traces, SQL statements, or internal filesystem/configuration paths in the HTTP response body.
- **API-03 - Boundary validation before dispatch:** Invalid HTTP payloads or query/path parameters are rejected with `400` (`validation_failed` or `malformed_request`) before invoking `OperationDispatcher` or opening a transaction segment.
- **API-04 - Explicit domain error mapping:** Application services return typed results (`Result<T, E>` or `Optional<T>`) rather than throwing HTTP exceptions or returning Spring web types. Controllers map domain error values explicitly to HTTP statuses and problem codes.
- **API-05 - Fail-fast typed configuration:** Application configuration properties are bound to immutable records and validated on context startup; missing or out-of-range settings abort startup with a clear diagnostic.
- **API-06 - Health and readiness:** The health endpoint returns `200 OK` when the application and its configured datasource are reachable and `503 Service Unavailable` when readiness checks fail, without requiring authentication.
- **API-07 - Deterministic test separation:** Default unit and web-slice tests execute completely offline without a running PostgreSQL instance. Database-dependent tests are isolated by tag/profile per P1.3c and never pass silently against H2.
- **API-08 - Direct same-origin `/api/*` forwarding without BFF routes:** Next.js contains no per-endpoint `/api/*` route handlers (`app/api/` is removed); all `/api/:path*` requests are rewritten directly to the Spring Boot backend with HTTP methods, headers, cookies and error bodies intact.
- **API-09 - Typed frontend client errors:** The frontend API client surfaces both backend `ProblemDetail` responses and network/502 failures as typed `ApiError` instances without swallowing status codes or field validation details.

## Acceptance criteria and test cases

| ID | Setup and action | Expected evidence | Verification |
| --- | --- | --- | --- |
| **P1.4-T01** | Send a request with invalid fields and a request with malformed JSON to a validated controller endpoint. | Returns `400` with `application/problem+json`, `code: "validation_failed"` (with `fieldErrors`) and `code: "malformed_request"` respectively; `OperationDispatcher` is not invoked. | Backend MockMvc / component test |
| **P1.4-T02** | Dispatch a controller action whose application service returns a typed `Result.Err` (e.g., not-found or conflict) vs. throws an unexpected `RuntimeException`. | `Result.Err` maps to the declared status (`404`/`409`) and domain `code`; unexpected exception returns `500` with `code: "internal_error"` and no stack trace or internal class name in the body. | Backend MockMvc / component test |
| **P1.4-T03** | Start a minimal Spring context with valid vs. invalid `@ConfigurationProperties` values. | Valid properties bind into the immutable record; invalid properties fail context startup deterministically. | Backend Spring context test |
| **P1.4-T04** | Call the health endpoint when the backend is healthy and when a readiness check reports failure. | Returns `200 OK` with status `"UP"` when healthy and `503 Service Unavailable` with status `"DOWN"` when unhealthy. | Backend component test |
| **P1.4-T05** | Configure the `/api/:path*` rewrite in `next.config.ts`, remove `app/api/greetings/route.ts`, and issue a same-origin `/api/...` request to the backend. | Request reaches the Spring Boot backend with headers, cookies, status code and `ProblemDetail` body preserved, with no Next.js `app/api` route handler in the codebase. | Frontend / integration verification |
| **P1.4-T06** | Invoke the frontend API client against mocked `200 OK`, `204 No Content`, `400` (`ProblemDetail` with `fieldErrors`), and network failure responses. | Resolves typed data on success; throws/returns typed `ApiError` with preserved `status`, `code`, and `fieldErrors` on failure. | Frontend unit test / build & lint verification |
| **P1.4-T07** | Run `./mvnw test` (backend) and `npm run lint && npm run build` (frontend) offline. | Both succeed cleanly with zero warnings/errors and `git diff --check` clean. | CLI verification |

## Open decisions and deferrals

- **Dependency handoff vs. zero-dependency implementation:** The current `pom.xml` includes `spring-boot-starter-webmvc` (which provides `ProblemDetail`, `@RestControllerAdvice` and Jackson) but does not yet declare `spring-boot-starter-validation` or `spring-boot-starter-actuator`. During implementation planning, decide whether to add those starters and refresh the offline dependency archive or implement P1.4a using the already cached Spring MVC primitives first.
- **Starter greeting endpoint path:** Align the starter backend `/greeting` endpoint under `/api/...` (or replace the starter fetch with `/api/system/v1/health`) when removing `src/frontend/app/api/greetings/route.ts` so the starter page continues to work through the `/api/:path*` rewrite until Phase 2 replaces it.
- **Deferred to Phase 2 and Phase 8:** Spring Security filter chains, CSRF cookie issuance (`GET /api/iam/v1/csrf`), bearer token refresh, cross-tab Web Locks coordination (P2.1/P2.3), and the production Next.js static export (`output: 'export'`) with Nginx proxying (P8.1) remain out of scope for P1.4.

