# Engineering review checklist

Use selectively for nontrivial changes. Review the surrounding call graph and actual executed paths, not only
the diff.

- **Behavior:** established contract, edge cases, input normalization, backward compatibility, valid duplicate
  handling, deterministic output, ordering and precedence.
- **State:** logical identity, version/ownership consistency, stale updates, lifecycle transition legality,
  invariants after every exit path.
- **Concurrency:** locking boundaries, compare-and-swap/expected revisions, cancellation, retries, idempotency,
  no orphaned state after rollback or partial write.
- **Integration:** cross-module contracts, event ordering/deduplication, permissions, separate transaction
  scopes, drift/reconciliation, negative-path behavior.
- **Security:** authorization enforcement at actual mutation/read boundary, fail-closed unknown states,
  output/data exposure, untrusted strings, parser ambiguity, token/log safety.
- **Performance:** database-side filtering/projections, materialization, N+1 access, query plans, indexing,
  redundant data transformations, connection lifetime.
- **Quality:** cohesive names/packages, needless indirection, maintainable extension paths,
  implementation-appropriate idioms, tests for previously unobserved failure paths, steady-state documentation.
- **Validation:** actual compilation/test outcomes, warning/static-analysis issues, diff whitespace checks,
  environmental limitations, patch applies cleanly to stated baseline.
