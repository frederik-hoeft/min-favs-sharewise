# P2.1c - Integrated acceptance evidence

**Status:** Integration and review in progress. The maintainer reported a green full merge-request pipeline and encouraging limited manual testing on a local deployment on November 23, 2026. These observations support the integrated implementation but do not establish that every scenario in the [approved specification](specification.md#central-tests-and-acceptance) was individually exercised. Final independent review and merge authorization are pending under the [plan](plan.md#phase-f---independent-review-steady-state-documentation-and-merge-authorization).

## Automated evidence

The maintainer confirmed that the complete merge-request pipeline is green on the integrated P2.1c revision, including the frontend password form and the workspace authentication guard. The pipeline contains backend, PostgreSQL-backed and frontend gates, as described in the [backend test guide](../../../development/tests.md) and [frontend development guide](../../../development/frontend.md). The exact upstream commit, pipeline URL, job results and test counts were not supplied with that confirmation, so this record does not assign pass/fail status to individual test cases or reproduce isolated local test counts as integrated CI evidence.

## Manual HTTPS acceptance

The maintainer reported limited local-deployment testing with promising results. Specific observed outcomes, deployment commit, browser versions and exercised variants were not supplied. The earlier [P2.1b acceptance record](../p2.1b-session-lifecycle/acceptance.md) documents login, restoration, logout and cross-tab smoke behavior and identifies scenarios that lacked a protected API at that time; those observations remain useful but do not certify P2.1c functionality by themselves.

The remaining manual acceptance record should distinguish the following behaviors rather than treating green CI as equivalent browser evidence:

- Account profile read, display-name set/clear, disabled/errors and successful password replacement through the same-origin HTTPS deployment.
- Two independent browser sessions: the caller remains eligible to refresh, the other session cannot refresh after password change, and a fresh login accepts only the replacement password. Existing access JWTs remain valid only up to their bounded expiry unless separately checked against authoritative state.
- Previously deferred P2.1c.1 protected-operation paths: access-JWT expiry and renewal, repeated rotation, parallel tabs, account-context changes, revoked/expired refresh sessions, terminal failures and the validated `?next=` login return path.
- Uncertain responses and non-replay of credential mutations; distinguish pre-dispatch bearer `401`, post-dispatch `session_unavailable`, `403` denials, and failures requiring fault injection rather than casual UI clicks.

Mark a scenario passed only when its observed result and tested revision are recorded. Unexercised variants remain open acceptance evidence, not presumed failures or successes.

## Final decision

Independent security/architecture review, any accepted corrections, final manual-acceptance disposition and explicit merge authorization remain to be recorded. The reduced MVP deliberately excludes email changes, password recovery, self-service disablement, session/device management and permanent deletion (P2.1e).
