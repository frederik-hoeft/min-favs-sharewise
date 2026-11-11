# P1.3a - Implementation plan: operation-scoped DI

**Status:** Approved plan alongside the [P1.3a specification](specification.md). **Execution gate:** Each implementation phase still requires its own human review and approval before the next phase begins. The [AI development workflow](../../../development/ai-development-workflow.md) governs incremental human review and final acceptance.

## Baseline and prerequisites

The supplied snapshot contains a Java 25 / Spring Boot 4.1.2 Maven backend and a Next.js starter; no custom operation scope, dispatcher, transaction port or PostgreSQL fixture is implemented. P1.3a is **backend infrastructure**, and its DI tests require neither the frontend nor a running database. Changes are made from the approved Git baseline on an isolated feature branch, with a clean patch and recorded validation at each review gate.

The scope implementation uses Spring's existing `BeanFactory` and custom `Scope` SPI with Java 25 `ScopedValue`, retaining the established names `ServiceScopeFactory`, `ServiceScope`, `ServiceProvider` and `OperationDispatcher`. The [implemented DI architecture](../../../architecture/dependency-injection.md) defines the current steady state; future integration plans remain in [docs/stack](../../../stack/dependency-injection.md). P1.3b and P1.3c integrate later; do not create placeholder transactions, test inspection ports or database infrastructure to make this task compile.

**Phase completion rule:** Check a task box only when its changes have been validated **and human-approved**, not when code has merely been written. Each phase produces a separate reviewable patch, remains unmerged until approval and reports exact test commands and limitations. On architectural deviation, revise and review `specification.md` before replanning.

## Phase A - Spring scope and explicit lifetime core

**Covers:** DI-01 through DI-05, DI-09, DI-11; DI-T01 through DI-T05 and DI-T11 (relevant portions).

- [x] Register the custom `operation` Spring scope in the application context early enough for scoped bean construction, without creating another bean factory. Keep the registration and implementation in a cohesive backend infrastructure package.
- [x] Implement the per-context `ServiceScopeFactory`, independently created `ServiceScope` instances, and the private `ScopedValue` key. Cache beans by Spring bean name; ensure no partially created bean is cached on factory failure.
- [x] Implement Spring `Scope` methods including destruction callback registration, conversation/context behavior and a deliberate `remove` contract. Preserve Spring ownership of construction and bean post-processing.
- [x] Add the scope-bound `ServiceProvider` facade that delegates directly to Spring's `BeanFactory` and validates owner/current/open state. Implement one-shot lexical `run(...)` activation and terminal `AutoCloseable` cleanup, with independent nested DI scopes supported.
- [x] Add focused Spring tests for constructor-injected shared identity, singleton reuse, different scopes, normal Spring `@Bean`/qualifier handling, no active scope, provider misuse, nested bindings and explicit removal/creation failure.

**Gate A:** From `src/backend/sharewise`, `mvn -B -ntp -o "-Dmaven.repo.local=$MAVEN_REPO" test` and the focused Spring test suite pass using the supplied offline cache. Every scoped bean is created by Spring rather than a parallel reflection activator; opening, binding and closing a scope leave no cross-scope cache state. The code diff is reviewed before the next phase.

## Phase B - Production dispatcher and lifecycle failure guarantees

**Covers:** DI-05 through DI-10 and DI-12; DI-T02, DI-T05 through DI-T10.

- [x] Implement singleton `OperationDispatcher` as the production entry point. Resolve one requested entry type inside its fresh scope, invoke the typed function, and always close the scope. Do not begin or intercept any physical transaction.
- [x] Reject recursive business dispatch while a scope for the same context is active. Permit ordinary calls among already-injected collaborators; do not prohibit the low-level factory's isolated nested DI scopes.
- [x] Enforce owner-thread rules on activation, resolution and closing, even if a `ScopedValue` binding was inherited by a structured child. Reject reentrant and repeated activation of the same scope instance.
- [x] Make teardown exception-safe: invoke all registered callbacks, clear references even after failure, preserve business exceptions as primary with cleanup failures diagnosable, and disallow resolution after closure. Account for Spring lifecycle callbacks that log rather than propagate exceptions.
- [x] Add tests for exceptional entry-service construction/invocation, failed destruction callbacks, independent concurrent operations, wrong-thread attempts, after-close attempts and original binding restoration.

**Gate B:** Dispatcher and failure-path tests pass through the real Spring context, including cleanup on failure. `git diff --check` is clean, no tracked starter behavior is changed, and the phase patch has been reviewed and accepted.

## Phase C - Integration seam and regression checks

**Covers:** DI-03, DI-04, DI-07, DI-08, DI-12; DI-T03, DI-T06, DI-T08, DI-T12.

- [x] Verify independent Spring application contexts each own a distinct factory, binding key and bean graph. Cover activation in A while B's scoped lookup is attempted, then normal activation in B.
- [x] Exercise the test-facing **same-scope** seam: an integration-style callback resolves a service and several collaborators through one scope-bound provider, asserts their state and exits cleanly. Do not add a P1.3c transaction harness or second registry.
- [x] Verify ordinary Spring bean features needed by future components (constructor injection, qualified candidates, factory methods, initialization and destruction). Identify any unsupported optional advanced features explicitly rather than implementing duplicate Spring plumbing.
- [x] Verify tests and developer guidance do not claim comprehensive singleton-capture validation, escaped-reference invalidation, automatic prototype disposal, or nested business dispatch.

**Gate C:** Targeted tests and the full backend Maven `clean verify` pass strictly offline with supplied dependencies. Test contexts have no cross-instance scope leakage and the DI test seam requires no PostgreSQL. Review the entire implementation diff and update the checklist only after human acceptance.

## Phase D - Documentation reconciliation and acceptance handoff

**Covers:** All DI requirements and test cases; documentation/acceptance gate.

- [x] Move verified DI contracts into the steady-state [DI architecture](../../../architecture/dependency-injection.md), retain future integrations in the [stack notes](../../../stack/dependency-injection.md), and reconcile the [alternative rationale](../../../stack/dependency-injection-alternatives.md), [developer guidelines](../../../development.md), and any affected P1.3b/P1.3c references with **verified implemented behavior**, including API signatures, scope ownership, exceptional cleanup, permitted nested DI scopes and forbidden nested dispatch.
- [x] Document concrete backend usage and testing examples in the appropriate existing developer documentation, without copying this plan into system architecture documents. Retain clear distinctions between implemented DI and still-planned transaction/test infrastructure.
- [x] Run full backend offline verification, relevant doc/link checks, and `git diff --check`; record any unavailable tools or untested integrations. Check no unrelated file or lockfile changed.
- [x] Provide final focused patch, verification evidence, open limitations, and an infrastructure-oriented manual acceptance script for the human reviewer. Keep the feature branch unmerged until final approval.

**Gate D:** The human reviews the complete branch and documentation, executes/reviews the scoped Spring acceptance workflow, and authorizes the merge only after the specification and implementation evidence agree. Mark [P1.3a](../backlog.md#phase-1---modular-application-and-persistence-foundation) complete only as part of the accepted pre-merge update.

## Scope boundaries and deferred work

P1.3b owns transaction-segment execution and failure semantics; P1.3c owns rollback-isolated and real-commit test fixtures. Automatic singleton-capture detection, automatic invalidation of arbitrary escaped Java objects, independent child bean factories, nested business operations, per-operation transaction creation and generalized async propagation are outside this plan. Any future best-effort lifetime validation is a separately reviewed improvement rather than a reason to delay the initial operation DI foundation.
