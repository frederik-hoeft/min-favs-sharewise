---
name: engineering-quality
description: Plan, implement, refactor, and review nontrivial software changes with invariant-driven design, explicit ownership, strong tests, careful API and transaction semantics, and reviewable patches. Use for coding, architecture changes, migrations, bug fixes, and PR reviews in any language or framework.
---

# Engineering quality

Prioritize correctness, coherent architecture, maintainability, testability, and long-term evolution over a
quick result that happens to compile. Apply this skill to any codebase; **follow project-specific contracts and
established conventions first**. Do not mechanically impose ShareWise, .NET, DDD, or any other project's
patterns on unrelated code.

## Before changing code

1. Inspect the repository, relevant documentation, tests, dependency wiring, and neighboring implementations.
   Map the actual data/control flow, lifetime boundaries, and public API before editing.
2. Determine observable behavior, explicit invariants, consistency boundaries, failure modes, compatibility
   constraints, and the users of any contract being changed. Differentiate established requirements from
   speculative enhancements.
3. Choose the smallest **coherent and durable** design, not the fewest lines. Prefer an incremental task with an
   inspectable diff; avoid unrelated cleanup and gratuitous migration.
4. Consider alternatives when they genuinely affect correctness, coupling, performance, or extensibility. State
   the selected tradeoff; do not design a large framework without a concrete need.

## Architecture and API design

- Keep responsibilities, state ownership and dependencies explicit. A component owns its writes; other
  components access published contracts. Avoid circular or hidden cross-module dependencies and incidental
  shared mutable state.
- Favor small, cohesive operation-oriented services and interfaces **where the boundary is meaningful**. Do not
  create pass-through services, interfaces-per-method, facade chains, or abstraction factories solely to satisfy
  an architectural diagram.
- Design contracts around domain meaning, not ORM shape, storage columns, framework-specific types, or a
  convenient present implementation. Do not leak database entities into public transport or domain APIs.
- Keep data transfer/query projections narrowly tailored. Execute filters, joins, aggregations and conditional
  updates close to the data when appropriate; avoid N+1 calls, materializing complete graphs, or copying
  collections unnecessarily.
- Separate business decisions from execution mechanics. Adapters may efficiently enforce core-specified
  invariants, but must not invent contradictory business rules. Treat external inputs and consumed integration
  events as untrusted.
- Prefer clear, explicit data flow and lifecycle ownership over implicit ambient state. When using DI, choose
  lifetime and composition deliberately; keep shared services stateless/thread-safe and operation state
  operation-local.
- Reuse existing well-formed abstractions and patterns rather than duplicating parsers, validators, state
  machines, or data models with ad hoc string parsing and positional assumptions.
- Preserve behavior and compatibility unless the change explicitly authorizes a break. Remove redundant code
  when safe; resist adding complexity for hypothetical needs.

## Correctness, concurrency and security

- Specify invariants at appropriate boundaries (construction, aggregate/operation, persistence, integration) and
  verify **all** mutation paths preserve them, not only the happy path.
- Trace ordering and state transitions through retries, cancellation, conflicting edits, duplicate inputs,
  partial failures, cleanup, and concurrent calls. Distinguish local guarantees from eventually consistent or
  networked state.
- Require explicit commit/rollback ownership and atomicity when multiple writes form one business operation. A
  successful sub-operation must not silently authorize an enclosing failed operation to commit. After
  transaction-fatal failures, fail closed; do not continue using poisoned sessions.
- Model expected rejections as typed results/diagnostics when practical (not-found, conflict, invalid
  transition). Reserve exceptions for unexpected faults, broken contracts, or errors that cannot safely be
  represented as ordinary outcomes. Never suppress unexpected exceptions to disguise a failed operation.
- Validate at trust boundaries, including cross-context identity/authorization, deserialization, external data
  and permission checks. Do not equate possessing a resource identifier with having access.
- Preserve temporal/historical invariants, stable logical IDs, provenance and ordered revisions when the
  business depends on them. Avoid subtly changed semantics through optimization or refactor.
- Be intentional about resource lifetime, disposal, thread safety, async/cancellation and error propagation. Do
  not swallow diagnostics or replace a structural problem with a fragile workaround.

## Maintainable implementation

- Prefer precise names and cohesive packages/namespaces. Split crowded modules by responsibilities when it
  reduces navigation cost or coupling; do not proliferate microscopic types for their own sake.
- Write idiomatic code for the *actual* language/runtime/toolchain; use modern standard language/library
  facilities when supported. Avoid importing familiar C# patterns into Java (or vice versa) without considering
  ecosystem conventions.
- Favor explicit invariants, typed models, declarative validation and structured errors over scattered manual
  checks. Avoid exception-driven expected control flow.
- Keep diffs focused, formats consistent, source easy to review, and documentation focused on **steady-state
  behavior** rather than a changelog. Explain why non-obvious contracts or decisions exist; describe what a
  component does before listing exclusions.
- Resist hidden allocations, redundant materialization, redundant dependency layers, or premature
  generalization. Maintain reasonable production performance without sacrificing clarity or correctness.

## Tests and review

1. Add focused tests for new/changed behavior and important invariants. Include invalid and hostile inputs,
   boundary values, concurrency/races, duplication/idempotency, rollback/commit, and retry/recovery cases as
   relevant.
2. Test **observable contracts** without requiring one preferred implementation. Unit-test pure rules;
   integration-test SQL/ORM/locking, serialization and real adapter behavior where mocks would miss bugs. Add
   regression tests for defects and changed edge cases.
3. Run available build, test, format/static-analysis and architecture checks; distinguish
   environmental/toolchain limits from code failures. Do not claim to have run tools you could not run.
4. Review the full final diff, not only edited lines. Trace interactions with callers, persisted state, parallel
   operations, integration consumers, and docs. Prioritize correctness and unintended regressions over minor
   stylistic preferences.
5. Report meaningful findings with precise file/contract references, severity and reproducible reasoning. If
   implementing, provide a concise summary, validation matrix, reviewable commit/patch and unresolved risks.
   Never bury significant defects in a list of optional polish.

## Handoff discipline

Use the repository's documented approval workflow. Keep an approved baseline separate from unreviewed changes;
bundle tightly related steps into coherent patches, and stop at a reviewable boundary. Don't merge or publish
external changes without the requested approval. Document steady-state contracts and brief
implementation/testing constraints so another maintainer can continue without reconstructing an entire
conversation.

## Additional guidance

For a more detailed pre-merge checklist, consult [review-checklist](references/review-checklist.md). Do not
blindly apply every entry to trivial changes; scale validation to actual risk.
