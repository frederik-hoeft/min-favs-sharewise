# AI-assisted development and quality assurance

ShareWise uses a **planning-first, human-reviewed development workflow** for work performed with coding agents. The developer and agent discuss a task, establish testable requirements and business invariants, agree on a phased plan, and implement it through reviewable changes. Independent agent review and human acceptance complete the quality-assurance process.

This workflow applies regardless of the agent, IDE or code-hosting platform. The [ChatGPT development guide](chatgpt-development-workflow.md) describes the project-specific setup for ChatGPT's hosted workspace, offline toolchain handoff, Git patches and GitHub review mirror. Repository [engineering-quality](../../.agents/skills/engineering-quality/SKILL.md) and [documentation](../../.agents/skills/documentation/SKILL.md) guidelines govern the work itself.

## Roles and review gates

The human contributor owns task scope, priorities, architecture decisions, acceptance criteria and the final merge decision. The implementation agent proposes designs and produces code, tests and documentation for review. An **independent reviewing agent** examines the completed change without relying on the implementation agent's conclusions. The human separately reviews its findings and the full change.

| Stage | Reviewable evidence | Gate |
| --- | --- | --- |
| Task discussion | Agreed scope, constraints, alternatives and open decisions | Human agrees on the intended behavior |
| Requirements and test design | `requirements.md` with invariants, scenarios and expected outcomes | Human accepts the specification |
| Implementation planning | `plan.md` with phased work and verification criteria | Human approves the implementation sequence |
| Iterative implementation | Focused changes, tests, results and updated plan state | Human reviews and accepts each increment |
| Independent review | Merge-request findings and responses | Substantive findings are resolved or explicitly justified |
| Final acceptance | Complete diff, test evidence, documentation and manual checks | Human authorizes the merge |

Requirements and plans are task-specific review artifacts, normally kept under `docs/tasks/<task-name>/`. Durable system behavior belongs in the relevant [architecture](../architecture.md), [domain model](../data-model-design.md) or developer documentation. Task files do not replace the authoritative system descriptions.

## 1. Discuss the task and its boundaries

The initial discussion establishes the problem and expected user-visible or system-level outcome before implementation begins. The agent may propose alternatives and surface hidden constraints; the human selects the direction after reviewing trade-offs.

The discussion covers the affected bounded contexts, ownership and published interfaces, data and control flow, authorization, transaction boundaries, failure behavior, concurrency and compatibility where relevant. Existing code and documentation establish the starting point. Unresolved decisions are recorded explicitly, with implementation deferred where the missing decision affects correctness.

The result is a scoped task rather than an unrestricted instruction to refactor surrounding systems. Necessary adjacent changes become explicit dependencies or follow-up tasks.

## 2. Specify requirements, invariants and test cases

The task's `requirements.md` captures **observable behavior** and the conditions under which it must hold. Each requirement should be identifiable so implementation steps and verification evidence can refer back to it. A useful specification includes:

- **Scope and interfaces:** affected operations, callers, inputs, outputs, permissions and explicitly excluded behavior.
- **Business invariants:** preconditions, postconditions, ownership, ordering, concurrency and transactional guarantees; invalid operations must have defined effects on persistent state.
- **Failure behavior:** expected rejection, rollback, retry, idempotency and recovery semantics, including partial failures where applicable.
- **Acceptance cases:** representative successful, invalid, boundary and concurrent scenarios with setup, action, expected outcome and verification method.
- **Open questions:** remaining assumptions, trade-offs and decisions required before an affected phase can proceed.

Test cases are **specified with the requirements**, not retrofitted after implementation. They should distinguish domain/application tests from infrastructure integration tests and manual user-flow acceptance. Database constraints, locking and transactional guarantees require tests against the actual database; mocks cannot validate those behaviors.

For example, a financial-correction requirement can establish that a stale expected revision yields a conflict and commits neither a new financial revision nor its ChangeRecord or event-publication intent. The corresponding test supplies a stale revision, attempts the correction and verifies both the response and the unchanged persisted state. The relevant correctness property is explicit even if its eventual implementation spans several adapters.

The human reviews the proposed cases for missing invariants, invalid assumptions and security or failure-path omissions. The accepted specification is the basis for judging the implementation.

## 3. Plan reviewable implementation phases

A `plan.md` translates accepted requirements into a sequence of cohesive changes. Each phase identifies its scope, prerequisites, affected contracts, expected tests and **completion gate**. Markdown checkboxes track progress, but a checked box represents verified work, not merely code written.

The first phases establish only the infrastructure needed by the current requirements. Later phases add behavior and integration, with documentation and acceptance checks included in the plan. The plan should make it possible to stop after a phase with a coherent, buildable state.

Requirements or design changes are reviewed and recorded before the affected plan is revised. An agent does not advance to future phases solely because it anticipates their implementation.

## 4. Implement, validate and review incrementally

For each phase, the implementation agent works from an **identified, reproducible Git baseline** and returns one focused change set. Its report identifies what changed, which requirements and tests it covers, the exact validation commands and outcomes, and any limitations or outstanding work.

Automated validation is proportionate to the change: formatting and static analysis, focused unit tests, integration tests for real persistence or transport behavior, and failure or concurrency tests for critical invariants. The agent distinguishes actual test failures from unavailable infrastructure and does not claim simulated integrations establish production-system guarantees.

A human examines the diff and test evidence **before committing**. The review checks correctness against the specification, architecture boundaries, maintainability, unintended changes and test quality. Changes may be partially accepted, revised or rejected. Follow-up patches are incremental against the state actually accepted, and the next phase begins only after the current gate passes.

## 5. Review the finished merge request independently

After implementation phases pass local review, a separate reviewer examines the complete merge request in the context of the relevant requirements and architecture. The review follows affected call paths, including interactions beyond modified lines, and checks security, invariants, error handling, concurrency, data consistency, maintainability and missing tests. The repository's [review checklist](../../.agents/skills/engineering-quality/references/review-checklist.md) supports this pass.

Findings identify a concrete condition, its consequence and a suggested correction when possible. Confirmed defects are distinguished from design preferences or speculative enhancements. The human and implementation agent evaluate each finding, apply accepted fixes as reviewable increments, and request another review of the updated revision where necessary. Reviewer and implementer refer to the **same current commit**, so resolved or outdated comments are not mistaken for live defects.

An independent agent review supplements human review. The human remains responsible for acceptance and merge authorization.

## 6. Complete acceptance and maintain documentation

Before merging, the human verifies the complete change against the accepted requirements, business invariants and test cases. For user-facing behavior, this includes a manual workflow check. The final review includes the full diff, test evidence, unresolved risks, applicable deployment or compatibility implications, and steady-state documentation.

The authoritative documentation describes the resulting system without narrating the implementation sequence. Accepted task requirements and plans remain available for traceability. The merge occurs in the project's authoritative repository after the human acceptance gate.
