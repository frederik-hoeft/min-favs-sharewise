# P2.1b Phase E: browser smoke acceptance and final review

**Status:** Manual HTTPS browser **smoke stage accepted** by the maintainer on November 22, 2026. The results below are observations, not exhaustive end-to-end verification. Independent security review, final integrated CI evidence and merge authorization remain open. The [approved specification](specification.md#central-test-and-acceptance-design) defines the complete correctness contract.

## Environment and scope

The maintainer exercised the locally deployed ShareWise frontend and IAM backend through the same-origin HTTPS deployment with a PostgreSQL instance. The application workspace currently consists of demonstration pages rather than authenticated Group/Finance operations, so a protected business request cannot yet drive bearer-expiration recovery in an ordinary browser journey. The frontend's mocked transport tests and the backend's PostgreSQL/HTTP tests cover many of those transitions independently, but do not substitute for a live protected-operation check.

Browser version, tested application commit and complete final CI pipeline identifiers were not provided for this P2.1b smoke pass. [P2.1a's live acceptance record](../p2.1a-registration-auth/acceptance.md) documents earlier trusted-HTTPS and refresh-cookie attribute inspection. The maintainer reconfirmed that the refresh cookie's attributes still appear correct during P2.1b testing, without performing a new comprehensive cookie/credential-storage audit.

## Observed browser scenarios

| Scenario | Result | Evidence and limits |
| --- | --- | --- |
| Login, reload restoration and new-tab restoration | **Pass** | Authentication restored without another login form; tabs in the same browser context share the cookie-backed session. |
| Separate browser profile/private context | **Pass** | Authentication was not shared across independent browser contexts. |
| Logout, reload after logout and later login | **Pass** | Sign-out removed the browser refresh cookie, local state became unauthenticated, reload did not restore the old session, and a subsequent login succeeded. |
| Cross-tab session sharing and logout notification | **Pass** | Signing out in one tab also invalidated the other tab's local authentication context. The demonstration workspace can remain visible without a JWT; a protected-route redirect policy is not exercised by that UI. |
| Account/context switching and explicit reload | **Partial** | The maintainer observed context-change handling and deliberate reload restoration, including a new session for the same account. Account switching while a second tab was already authenticated was not conclusively exercised across all variants. |
| Transparent access-JWT renewal and repeated rotation on protected requests | **Blocked** | No production protected business endpoint exists for an ordinary browser request; elapsed token lifetime alone is not evidence of a renewed authorized operation. |
| Concurrent cross-tab renewal on expired access JWTs | **Blocked** | No protected business request drives the concurrent `401`/renewal path in the demo workspace. |
| Expired/revoked refresh credential, lost-response paths and terminal retry limits | **Not manually exercised** | These need controlled fault injection or an authenticated business operation; automated application, HTTP and persistence tests provide separate evidence. |
| Login `next` navigation and prevention of business-mutation replay | **Blocked** | Protected application-page navigation and business mutations are not present in the demonstration workspace. |
| Refresh cookie attributes | **Previously verified / rechecked informally** | P2.1a recorded `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no `Domain` and actual browser storage. The maintainer reported that these attributes still appear correct. |

The absence of a refresh cookie is intentionally indistinguishable to frontend JavaScript because it is `HttpOnly`. A tab without a local bearer may attempt one lazy restoration even when logged out; IAM returns an authentication failure if no usable cookie exists. This is an expected recovery outcome, not an automatic retry loop.

## Automated evidence and outstanding review

| Gate | State | Evidence needed for completion |
| --- | --- | --- |
| IAM lifecycle Unit, PostgreSQL persistence and HTTP component tests | **Implemented; prior review evidence** | Reconcile test results for the integrated revision rather than carrying forward isolated phase counts. |
| PostgreSQL Integration/E2E in CI at 16 JUnit workers | **Follow-up verification outstanding** | Confirm the complete pipeline after the test-only Hikari pool bound; a prior run exhausted PostgreSQL connection slots. |
| Frontend lint, production/test typecheck, Vitest, Next.js and static export | **Earlier Phase C CI reported green** | Confirm the final integrated merge-request pipeline. |
| Independent security and architecture review | **Pending** | Review complete IAM rotation/HTTP/browser call paths, race ordering, ambiguous outcomes and token/cookie handling; record and resolve findings. |
| Final merge approval | **Pending** | Maintainer reviews the full integrated diff, evidence and accepted limitations, then explicitly authorizes merge. |

The remaining manual cases may be revisited when Group or another real protected API becomes available. This does not require adding a production-only test endpoint or relaxing bearer ingress rules to make the demo pass. Scheduled refresh-history cleanup, device/session-management UI, stateful access-JWT admission and optional introspection remain outside P2.1b.

**Acceptance decision:** The observed Phase E functional **smoke-test stage is accepted**, with the explicitly blocked and untested cases retained as limitations. This acceptance does not itself close P2.1b/P2.1b.1 or authorize their final merge.
