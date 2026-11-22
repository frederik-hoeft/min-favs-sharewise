# P2.1a Phase G: live acceptance and final review

**Status:** Phase G manual browser acceptance completed and confirmed by the maintainer on November 22, 2026. The F1 PostgreSQL-backed HTTP/CSRF E2E gate was accepted on November 21, 2026. Final merge remains conditional on the merge-request CI pipeline passing and explicit merge authorization. The task's [requirements and acceptance scenarios](specification.md#requirements-and-invariants) remain authoritative.

## Automated evidence and outstanding checks

| Gate | Evidence or command | State |
| --- | --- | --- |
| Backend Unit/Modulith/package | `cd src/backend/sharewise && ./mvnw -Punit-tests clean verify` | Local strict-offline run on reconstructed PR head `39b05c35` (November 22, 2026): **251 tests passed**, including module checks; MR pipeline remains the merge gate |
| PostgreSQL-backed Integration | `./mvnw -Pintegration-tests clean verify` with PostgreSQL 18 | Prior B/C approval; rerun against final branch |
| PostgreSQL-backed E2E | `./mvnw -Pe2e-tests clean verify` with PostgreSQL 18 | **Green in maintainer-confirmed F1 CI**; includes `IamHttpAuthenticationE2E` |
| Frontend lint / type checks | `cd src/frontend && npm ci && npm run lint && npm run typecheck && npm run test:typecheck` | Enforced by the merge-request pipeline; must be green before merge |
| Frontend tests / builds | `npm run test && npm run build && npm run test:static-export` | Enforced by the merge-request pipeline; must be green before merge |
| Source hygiene | `python3 scripts/ci/check-source-encoding.py`, `git diff --check`, repo Markdown-link validation | Required on final patch |
| Real browser, real HTTPS backend | Procedure below, using production signing and a disposable PostgreSQL database | **Passed; maintainer confirmed completion November 22, 2026** |
| Independent code review and merge authorization | Review final diff and residual risks | Review findings addressed; merge authorization remains pending |

A green E2E CI job demonstrates backend HTTP/transaction/cookie behavior through a servlet and PostgreSQL, **not** browser cookie persistence, real Web Locks, frontend accessibility, or the reverse proxy in a production-style topology. The merge-request pipeline runs automatically and must pass at the merged candidate revision; its result is an enforced merge condition, not a prerequisite for recording completed manual acceptance.

## Manual browser acceptance: executable workflows (IAM-AT14/15)

Complete the checkboxes **against the running application**, not against mocks or screenshots alone. A check is complete only when its *expected outcome* was observed. If a step fails, record the defect and leave that checkbox unchecked. Record a workflow as **Pass**, **Fail**, or **Blocked** in the evidence table below; a blocked workflow does not pass by inference from CI.

### Test environment and evidence

Use a disposable PostgreSQL 18 database; a backend with `sharewise.iam.auth.enabled=true` and a correctly provisioned **production** ES256 issuer/audience/signing/verification profile; and the current frontend on one trusted **HTTPS origin** with same-origin `/api/*` proxying. Use an isolated browser profile without prior ShareWise cookies. Do **not** bypass TLS warnings, turn off security filters, or use fixture issuers. See [initial IAM setup](../../../development.md#initial-iam-authentication-p21a) and the [deployment guide](../../../../deploy/README.md).

- [x] Confirm the frontend is served over trusted HTTPS and that the deployed `/api/*` proxy targets the **same origin** (not a separate backend origin). The authentication workflows below will exercise `/api/iam/v1/auth/csrf`; no standalone bootstrap request is needed for setup.
- [x] Confirm the backend uses disposable test data and the approved production IAM signing/trust configuration; avoid reusing a real account or password.
- [x] Open an isolated browser profile. In DevTools **Network**, enable **Preserve log**, filter to `iam/v1/auth`, and keep the **Application/Storage → Cookies** view available.
- [x] Record the browser/version, backend/frontend commit IDs, test origin (no credentials), viewport sizes and tested themes in the report.
- [x] Prepare **two unique test email addresses** at a nonproduction domain and a password of 8–256 UTF-16 code units. Never paste passwords, access JWTs, `Set-Cookie` values or signing keys into evidence.

**Evidence/environment:**

Browser/version:

```text
Brave 1.100.58 (Official Build) (64-bit)
Chromium: 158.0.8127.42
```

Recorded browser-tested revision: `3823f2a` (P2.1a IAM signing-key provisioning). The checked workflow observations were recorded at that revision, and the maintainer confirmed the overall manual acceptance complete on November 22, 2026. Subsequent changes are covered by review and the required merge-request regression pipeline; this record does not claim a separate manual browser rerun at `39b05c35`.

### Workflow 1: Validate the registration form before network submission

**Start:** New tab at `/auth/register` in the isolated profile; DevTools Network cleared.

- [x] Using **Tab** and **Shift+Tab**, reach the email field, password field, **Create account** button, the **Sign in** link and the home link in a logical order. Focus is visible.
- [x] Submit the empty form using keyboard (**Enter** or Space on the button). **Expected:** email gets focus, "Enter your email address." is shown, and **no** `/auth/csrf` or credential POST occurs.
- [x] Enter a syntactically invalid address (for example `not-an-email`) and a valid test password, then submit. **Expected:** inline email error, focus on email, **no** IAM HTTP call.
- [x] Enter a valid address and a **7-character** password, then submit. **Expected:** "Password is too short.", password receives focus, **no** IAM HTTP call.
- [x] Try a **257-character** password. **Expected:** too-long feedback and **no** IAM HTTP call. Confirm the help text says 8–256 characters.
- [x] Correct the fields. **Expected:** previous field errors clear as edited, without losing keyboard focus or moving the fields unexpectedly.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 2: Register an account and verify the real HTTP contract

**Start:** Valid, never-before-registered email and password on `/auth/register` (a mixed-case email with leading/trailing spaces is useful to exercise trimming). **Do not submit another registration with the same email yet.**

- [x] Submit **Create account** once. **Expected:** fields/button are disabled while pending; on success the browser navigates client-side to `/app` and shows **"Signed in for this tab"**.
- [x] In DevTools Network, verify **GET `/api/iam/v1/auth/csrf` occurs before POST `/api/iam/v1/auth/register`**; the GET returns `200`, a JSON `token`, and a `Cache-Control` policy preventing storage. Inspect the GET response cookie by **attributes only**: `__Host-sharewise-csrf`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no `Domain`.
- [x] Verify the POST has status **`201`**, an `X-XSRF-TOKEN` header corresponding to the just-fetched token, and an `Origin` matching the frontend HTTPS origin. The CSRF bootstrap should not have been performed before the authentication lock (see Workflow 7).
- [x] Inspect the registration response's JSON **keys only**: `accessToken`, `tokenType: Bearer`, `expiresAt`. **Expected:** no refresh-token or CSRF-cookie value in JSON and no credential value in UI errors or application logs.
- [x] In the registration response's **separate `Set-Cookie` headers**, verify that `__Host-sharewise-refresh` is issued and `__Host-sharewise-csrf` is **cleared** (empty value with `Max-Age=0` or an `Expires` timestamp in the past). **Do not copy cookie values.**
- [x] For the refresh cookie, confirm `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/` and **no `Domain`**. Confirm the lifetime is not longer than the configured initial session deadline (normally 7-day idle expiry, subject to the earlier absolute deadline).
- [x] In **Application → Cookies**, verify the browser actually retained the secure refresh cookie for this HTTPS origin and no longer has the prior CSRF cookie. **Expected:** browser behavior matches the wire headers, not merely a MockMvc test.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 3: Duplicate registration and field-level server feedback

**Start:** Navigate to `/auth/register` (a reload here intentionally clears the in-memory access JWT), using the email registered in Workflow 2.

- [x] Enter the same registered address using **different letter case** and/or surrounding whitespace, with a valid password, and submit.
- [x] **Expected:** `GET /auth/csrf` then `POST /auth/register` → **`409`** with code `email_unavailable`; the form displays **"This email address is unavailable."** and focuses the email field.
- [x] **Expected:** no access token in the failed response and **no new refresh `Set-Cookie`** header. An existing refresh cookie from an earlier success may still be present in browser storage; that is not a newly issued cookie.
- [x] Replace the email with a new unused test address. **Expected:** the duplicate-email message clears as you edit (you need not create a second account solely for this check).

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 4: Generic login denial and recovery

**Start:** `/auth/login`; use the real registered email from Workflow 2.

- [x] Enter the registered email with an incorrect password and submit using **Enter**. **Expected:** `GET /auth/csrf`, then POST `/auth/login` → **`401`**.
- [x] Verify the alert says **"We couldn't sign you in"** and does **not** reveal whether the account is unknown, locked or disabled. Keyboard focus moves to the error announcement.
- [x] Verify the failed response contains **no `accessToken`** and **no newly issued refresh `Set-Cookie`** header; the form remains usable afterward.
- [x] Repeat with a nonexistent email and an incorrect password. **Expected:** the **same generic user-facing denial**, without account-existence hints.
- [x] Edit a field and retry. **Expected:** stale error messaging clears and controls are usable. **Do not deliberately exhaust the account's lockout threshold** unless you have a separate controlled test account.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 5: Log in successfully with fresh CSRF and Strict cookies

**Start:** `/auth/login`; registered account; no active in-memory sign-in in this tab.

- [x] Submit the correct credentials. **Expected:** fresh **GET `/api/iam/v1/auth/csrf`** before POST `/api/iam/v1/auth/login` → **`200`**, followed by client-side navigation to `/app`.
- [x] Confirm `X-XSRF-TOKEN` and same-origin `Origin` are on the POST; token state is freshly bootstrapped even after earlier successful registration or login.
- [x] Inspect response JSON **keys only**: `accessToken`, `tokenType: Bearer`, `expiresAt`; there must be no opaque refresh secret in the body.
- [x] Verify **both** `Set-Cookie` headers survive: refresh cookie with `Secure; HttpOnly; SameSite=Strict; Path=/` and no `Domain`, plus an expired/cleared CSRF cookie. Tomcat may use a past `Expires` instead of `Max-Age=0`.
- [x] Confirm the Application/Storage cookie view reflects the issued refresh cookie and cleared CSRF cookie. The `/app` notice says **"Signed in for this tab"**.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 6: Memory-only session, reload, new tab and expiry

**Start:** Immediately after a successful login in Workflow 5; leave `/app` open.

- [x] Verify the workspace displays the **signed-in-for-this-tab** notice, without falsely claiming that the demo workspace provides protected account/Group data.
- [x] Inspect `localStorage`, `sessionStorage`, IndexedDB and browser cookies **by names/keys**, without exposing values. **Expected:** no persisted **access JWT**; `__Host-sharewise-refresh` is an opaque HttpOnly cookie, not a bearer-token cookie. The JWT is allowed in the successful auth **network response**, not persistent browser storage or HTML hydration.
- [x] Open `/app` in a **new tab** at the same origin. **Expected:** **"No active sign-in in this tab"** and a **Sign in** link, even while the shared HttpOnly refresh cookie exists.
- [x] Return to the signed-in tab and **reload** `/app`. **Expected:** the in-memory session is discarded and reauthentication is requested; no silent refresh call occurs.
- [x] Log in again and leave the tab open until the configured access-token expiry (normally **5 minutes**). **Expected:** session expiry is announced and the client requires sign-in again, without refresh/rotation or automatic replay.
- [x] Confirm no **working logout/refresh** workflow is represented as implemented in this P2.1a UI. If you see a protected API request in Network, check that it uses `Authorization: Bearer` and that **anonymous IAM auth** calls do not; if no protected API is exposed by the current demo, record **not observable in browser** rather than inventing a check.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 7: Web Lock serializes cross-tab authentication

**Start:** Two tabs, **A** and **B**, on the same HTTPS origin. Tab B shows `/auth/login`, prefilled with disposable valid credentials. Do not use multiple origins or separate private profiles; Web Locks must coordinate in the same browser storage partition.

- [x] In tab A's DevTools **Console**, run the following test-only lock holder. Wait for **"ShareWise test lock acquired"** before submitting in tab B:

```js
void navigator.locks.request('sharewise:iam:refresh-cookie', { mode: 'exclusive' }, async () => {
  console.info('ShareWise test lock acquired');
  await new Promise((resolve) => { window.__releaseSharewiseTestLock = resolve; });
});
```

- [x] In tab B, submit **Sign in** while A holds the lock. **Expected:** the form stays pending and **neither GET `/auth/csrf` nor POST `/auth/login` starts**. This proves the CSRF bootstrap runs *inside* the exclusive lock.
- [x] In tab A's Console, execute `window.__releaseSharewiseTestLock()` to release the lock. **Expected:** B now sends the CSRF GET, then the credential POST, and completes normally.
- [x] Repeat if desired with `/auth/register` and a **new** email. **Expected:** both mutation types use the same lock; no concurrent cookie-changing request bypasses it.
- [x] Verify the test lock is released; close/reload tab A if necessary. Record the observed ordering/timestamps **without request bodies or credential values**.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 8: Web Locks unavailable must fail closed

**Start:** New tab at `/auth/login` with DevTools Console and Network open. Use disposable credentials. The override below is **temporary to this tab**; reload the page to restore normal behavior.

- [x] Before submitting, execute `Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });` in DevTools Console. Verify `navigator.locks` is `undefined`.
- [x] Submit a syntactically valid login form. **Expected:** a visible actionable message mentioning **Web Locks**, with no CSRF GET and **no credential POST**. The form becomes usable after the error.
- [x] **Reload** the tab to restore native `navigator.locks`; verify normal login works afterward. If your browser blocks this test override, mark it **Blocked** and rely on the existing frontend fail-closed tests instead of disabling security on the backend.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** Maintainer confirmed the completed workflow on November 22, 2026.

### Workflow 9: Browser CSRF rejection with no real credentials

**Start:** Logged-out tab at the same trusted HTTPS origin. This test uses deliberately **invalid** credentials to avoid mutating an account, and runs entirely from the browser Console under the same Web Lock. Do not paste any real password or token into the Console.

- [x] In DevTools Console, run this safe negative probe. It obtains a real CSRF cookie but deliberately sends a **wrong header** on the login POST:

```js
await navigator.locks.request('sharewise:iam:refresh-cookie', async () => {
  const bootstrap = await fetch('/api/iam/v1/auth/csrf', { credentials: 'same-origin' });
  if (!bootstrap.ok) throw new Error(`CSRF bootstrap failed: ${bootstrap.status}`);
  const rejected = await fetch('/api/iam/v1/auth/login', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'deliberately-invalid' },
    body: JSON.stringify({ email: 'no-such-browser-test-account@example.test', password: 'intentionally-wrong' }),
  });
  console.info('Rejected HTTP status:', rejected.status);
});
```

- [x] **Expected:** the POST is rejected (normally **`403`**) before credential verification, with **no new refresh cookie** and no access token. The CSRF GET itself returns `200` and is non-cacheable.
- [x] Revisit the normal login form after the probe. **Expected:** it bootstraps its own new CSRF token and remains functional.
- [x] On the regular login/register POSTs in Network, verify the browser supplies an `Origin` matching the HTTPS application origin. **Note:** arbitrary `Origin` spoofing is restricted in browser JavaScript; the **negative Origin/missing-Origin** cases belong to the existing HTTP/E2E test suite, not this UI checklist.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Workflow 10: Accessibility, responsive layout and theme

**Start:** Visit **both** `/auth/register` and `/auth/login`; exercise empty-form, field-error, login-denial and success states with the test account.

- [x] Complete the forms **without a mouse**: Tab/Shift+Tab, Enter to submit, visible focus, reachable navigation links, and logical focus on the first invalid field.
- [x] Verify field errors and the generic authentication alert are readable and, using a screen reader if available, appropriately announced (alert/field association). A failure to announce errors is a defect, not a passing check.
- [x] Test a **narrow viewport (approximately 390 CSS px)** and a **desktop viewport (approximately 1280 CSS px)**. **Expected:** no horizontal overflow, cropped controls, hidden error messages, or obstructed submit button.
- [x] Test **200% browser zoom** and keyboard-only operation at both widths; focus indicators, form labels and error text remain usable.
- [x] Test **light**, **dark**, and **system** theme settings where offered. **Expected:** readable contrast and consistent feedback; if a mode is unavailable, record **N/A** rather than silently passing it.
- [x] Confirm the links **Sign in**, **Create an account**, **ShareWise home**, and **Learn more about ShareWise** navigate to their intended destinations.

**Result:** [x] Pass · [ ] Fail · [ ] Blocked. **Notes/defect:** ____

### Acceptance summary

| Workflow | Result (Pass / Fail / Blocked) | Evidence or defect reference |
| --- | --- | --- |
| 1. Registration client validation | Pass | Recorded workflow result; `3823f2a` |
| 2. Real HTTP registration/cookies | Pass | Recorded workflow result; `3823f2a` |
| 3. Duplicate account | Pass | Recorded workflow result; `3823f2a` |
| 4. Generic login denial | Pass | Recorded workflow result; `3823f2a` |
| 5. Login/CSRF/cookies | Pass | Recorded workflow result; `3823f2a` |
| 6. In-memory session and expiry | Pass | Recorded workflow result; `3823f2a` |
| 7. Cross-tab Web Lock | Pass | Recorded workflow result; `3823f2a` |
| 8. Missing Web Locks API | Pass | Steps recorded complete; maintainer confirmed acceptance November 22, 2026 |
| 9. CSRF negative probe and Origin observation | Pass | Recorded workflow result; `3823f2a` |
| 10. Accessibility/responsiveness/themes | Pass | Recorded workflow result; `3823f2a` |

**Evidence rules:** Store only sanitized status/response-header *attribute* observations and focus/layout screenshots. Do not capture token/cookie values, signing keys, credential request bodies or real passwords. A future failed/blocked workflow requires reacceptance; automated CI does not replace browser evidence.

**Explicitly outside browser-only acceptance:** The CI-backed PostgreSQL atomicity/lockout/race tests (IAM-AT01–AT08), cryptographic profile/issuer checks, missing/forged Origin rejection, operation-scope authorization and bearer-only Group-shaped routes, and absence of `UserRegistered`/Group-table writes must be signed off using the automated/architecture evidence below. The demo workspace currently does **not** provide a real Group API surface; do not manufacture browser evidence for it.

## Requirement-to-evidence trace

| Requirements / scenarios | Strongest present evidence | Final merge verification |
| --- | --- | --- |
| IAM-A01–A05; IAM-AT01–AT08 | IAM domain/credential Unit tests; `IamAuthenticationIT`, `IamAuthenticationE2E`, `IamPersistenceE2E` and database constraints | Required MR Unit/IT/E2E jobs must pass |
| IAM-A06–A09; IAM-A13; IAM-AT09–AT12 | `IamAuthenticationHttpTests`, `IamHttpAuthenticationE2E`, production JWT/credential tests | Manual HTTPS/browser acceptance confirmed complete |
| IAM-A07, A10, A14; IAM-AT10, AT13, AT17 | `FullApplicationIamIngressTests`, JWT/DI/Modulith tests, production-wiring tests | Review completed; required MR security/architecture tests must pass |
| IAM-A11; IAM-AT14–AT15 | Frontend `identity-runtime`, `auth-session`, `authentication-form` and API tests with mocked HTTP | Manual browser, Web Locks and accessibility acceptance confirmed complete |
| IAM-A12; IAM-AT16 | Context/module verification and IAM migration/repository inspection | Reviewed; event/bootstrap responsibility explicitly deferred to P2.4a/P2.2d |

## Bootstrap and follow-on handoff

**Group identity projection:** P2.1a commits users only to `identity.users`; it does not publish `UserRegistered` events, create `groups.users`, or populate Group memberships. Consequently, a future P2.4a/P2.2d listener cannot rely on an event registry to replay accounts created **before** publication begins. That integration must provide an explicit, idempotent initial projection from an authoritative IAM account snapshot, account for registrations racing with the snapshot/cutover, and reconcile missing/stale Group identities. A suitable cutover needs a proven no-gap boundary (for example, a transactional publication/outbox boundary plus snapshot reconciliation); it is **not** safe to enable a listener and assume all historical registrations appear automatically. Do not backfill across bounded contexts from this slice or infer eligibility from a signed JWT. Account deletion ordering and reconciliation require their separately reviewed policy.

**Refresh/logout handoff:** P2.1a creates one durable, unconsumed refresh-token verifier per initial session and issues the opaque refresh cookie. P2.1b consumes and rotates this existing token family, enforces replay/revocation and idle/absolute deadlines, and extends the current CSRF/Origin protection and `AuthCookieLock`/`AuthCoordinator` coordination instead of creating another cookie-mutation mechanism. The related [P2.1b.1 clock-skew/401 task](../backlog.md#p21---iam-vertical-slices) must replace the current `AuthSession` wall-clock rejection with backend-authoritative renewal and bounded retries, including multi-tab account mismatch protection. Neither refresh/logout nor live session eligibility exists in P2.1a; the latter remains P2.1d's responsibility.

**Merge gate:** Manual acceptance and independent review are complete. The implementation task can be marked complete in the backlog; merging remains conditional on successful required MR CI jobs and explicit authorization. The release/exposure limitations noted above remain follow-up work.
