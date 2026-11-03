# AI-assisted development and quality assurance

ShareWise uses a **planning-first, human-reviewed development workflow** for work performed with coding agents. The developer and agent discuss a task, agree on a target-state architecture and testable specification, plan implementation and migration, and develop through reviewable increments. Independent agent review and manual human acceptance complete the quality-assurance process.

This workflow applies regardless of the agent, IDE or code-hosting platform. The [ChatGPT development guide](chatgpt-development-workflow.md) describes the project-specific setup for ChatGPT's hosted workspace, offline toolchain handoff, Git patches and GitHub review mirror. Repository [engineering-quality](../../.agents/skills/engineering-quality/SKILL.md) and [documentation](../../.agents/skills/documentation/SKILL.md) guidelines govern the work itself.

## Roles and review gates

The human contributor owns task scope, priorities, approval of the architectural solution and specification, acceptance criteria, and the final merge decision. The implementation agent proposes designs and produces code, tests and documentation for review. An **independent reviewing agent** examines the completed change without relying on the implementation agent's conclusions. The human separately reviews its findings and the full change.

| Stage | Reviewable evidence | Gate |
| --- | --- | --- |
| Task discussion | Scope, constraints, architectural options and trade-offs | Human agrees on the direction to specify |
| Specification | Agent-written `specification.md` with the agreed architecture, requirements, invariants, test cases and acceptance criteria | Human approves the target-state architecture and specification |
| Implementation planning | Agent-written `plan.md` with implementation/migration phases, verification gates and final documentation reconciliation | Human approves the implementation sequence |
| Iterative implementation | Focused changes, tests and updated plan state | Human reviews changes as staged in Git or accepted/applied in the IDE, then approves each increment |
| Independent MR review | Findings against the specification and complete diff; suggested manual E2E scenarios where useful | Substantive findings are resolved or explicitly justified |
| Manual acceptance | Human-run local end-to-end test of the affected workflow, when applicable, and recorded results | Human confirms the user-visible behavior |
| Merge authorization | Complete diff, automated and manual test evidence, reconciled docs and outstanding risks | Human authorizes the merge |

**Proportionality.** The full sequence, including separate `specification.md` and `plan.md`, is the default for substantial features and medium-to-high-risk changes. Localized, low-risk changes may omit formal task documents and simplify planning or review stages when their intended behavior is clear. They still require appropriate verification, human review of the actual changes, relevant acceptance, and reconciliation of authoritative steady-state documentation. The necessary rigor depends on risk and impact, not simply on diff size.

Specifications and plans are task-specific review artifacts, normally kept under `docs/internal/tasks/<task>/`. Durable system behavior belongs in the relevant [architecture](../architecture.md), [domain model](../data-model-design.md) or developer documentation. Task files do not replace the authoritative system descriptions.

## 1. Discuss the task and its boundaries

The initial discussion establishes the problem, expected user-visible or system-level outcome, and target architectural direction before implementation begins. The agent may propose alternatives and surface hidden constraints; the human evaluates the trade-offs and agrees on the architectural solution to be specified.

The discussion covers the affected bounded contexts, ownership and published interfaces, data and control flow, authorization, transaction boundaries, failure behavior, concurrency and compatibility where relevant. Existing code and documentation establish the starting point. Unresolved decisions are recorded explicitly; architectural questions that affect correctness or the target design are resolved before the specification is approved.

The result is a scoped task rather than an unrestricted instruction to refactor surrounding systems. Necessary adjacent changes become explicit dependencies or follow-up tasks.

## 2. Agree on the architecture and specification

The task's agent-written `specification.md` records the **human-approved target state** before implementation planning. It captures the architectural solution reached during discussion alongside functional and implementation requirements, business invariants and acceptance criteria. Requirements and decisions are identifiable so implementation phases and verification evidence can refer back to them. The specification includes:

- **Architectural solution:** agreed component responsibilities and boundaries, data/control flow, published contracts, transaction and consistency boundaries, relevant technology choices, and consequences for existing interfaces or data. Record the material trade-offs and decision rationale; diagrams are useful where they clarify the design.
- **Scope and requirements:** intended behavior, affected operations and callers, inputs, outputs, permissions, compatibility, implementation constraints and explicitly excluded behavior.
- **Business invariants:** preconditions, postconditions, ownership, ordering, concurrency and transactional guarantees; invalid operations have defined effects on persistent state.
- **Failure behavior:** rejection, rollback, retry, idempotency and recovery semantics, including partial failures where applicable.
- **Acceptance criteria and test cases:** observable completion conditions and representative successful, invalid, boundary, concurrent and failure scenarios, each with setup, action, expected outcome and verification method. Include manual end-to-end workflows where applicable.
- **Open questions and deferrals:** non-blocking assumptions and explicitly excluded future decisions. Questions that would change the chosen architecture are resolved before approval.

Central test cases are **specified alongside the architecture and requirements**, not retrofitted after implementation. They should distinguish domain/application tests from infrastructure integration tests and manual user-flow acceptance. Database constraints, locking and transactional guarantees require tests against the actual database; mocks cannot validate those behaviors.

The test cases documented here focus on the *core* correctness properties of the system as defined by the task goals. Additional tests covering technical integration and unit-level behavior are designed and implemented independently by the agent as part of the implementation and verification process. This avoids the overhead of Test-Driven Development for every low-level detail while ensuring that the critical business logic is thoroughly validated.

For example, a documented test for expense submission might verify that expense creation is rejected when the corresponding financial group is not in the active state. On the other hand, independent unit tests for the expense entity might focus on non-business-critical behavior, such as ensuring null values are handled correctly, input validation is enforced, and default values are applied as expected.

The human reviews and **approves the architectural solution**, requirements, invariants, acceptance criteria and test cases. The accepted specification is the contract against which the implementation and final acceptance are evaluated. A material architecture change returns to this gate before the implementation plan changes.

## 3. Plan reviewable implementation phases

An agent-written `plan.md` translates the **approved architecture and specification** into a sequence of cohesive implementation and migration steps. The plan follows the repository's [engineering-quality guidelines](../../.agents/skills/engineering-quality/SKILL.md), accounting for existing code, ownership, dependencies, data migration, compatibility, failure behavior and validation. Each phase identifies its scope, prerequisites, affected contracts, expected component-level tests and a **completion gate**. Markdown checkboxes track progress, but a checked box represents verified and **approved** work, not merely code written.

The first phases establish only the infrastructure needed by the specification. Later phases add behavior, migration and integration, while preserving coherent, buildable states between phases. **The final planned implementation step reconciles the authoritative steady-state documentation** with the completed system, following the repository's [documentation skill](../../.agents/skills/documentation/SKILL.md). It updates affected architecture, domain and developer documentation, rather than copying the task plan into them. Any later review fixes that change documented behavior require the same reconciliation.

The plan is approved by the human after the specification gate. Requirement or architectural changes are reviewed in `specification.md` before affected phases are replanned. An agent does not advance to future phases solely because it anticipates their implementation.

For tasks using formal artifacts, approved `specification.md` and `plan.md` are **committed and pushed to the authoritative task branch before dependent implementation begins**. Material revisions are reviewed and committed before affected work continues; approval recorded only in a conversation or temporary workspace is not a reproducible Git baseline. Lightweight tasks document their intent and verification in the reviewed change or related issue.

## 4. Implement, validate and review incrementally

For each phase, the implementation agent works from an **identified, reproducible Git baseline** and returns one focused change set. Its report identifies what changed, which requirements and tests it covers, the exact validation commands and outcomes, and any limitations or outstanding work.

Automated validation is proportionate to the change: formatting and static analysis, focused unit tests, integration tests for real persistence or transport behavior, and failure or concurrency tests for critical invariants. The agent distinguishes actual test failures from unavailable infrastructure and does not claim simulated integrations establish production-system guarantees. At the end of each implementation iteration, all newly written code must have appropriate unit and integration tests.

The human reviews changes **as they are staged in Git, or as IDE-agent edits are accepted or applied**, rather than waiting for a completed phase or merge request and pretending that any human actually reads through a 20k line diff. Reviewing changes early and incrementally ensures that diffs remain manageable for human line-by-line review, that context is clear, and issues are caught early.

Review covers the specification, architecture boundaries, maintainability, unintended changes and test quality. Changes may be partially accepted, revised or rejected, and implementation only continues with the next iteration stage once the current stage is fully reviewed and any required corrections are applied. For the [ChatGPT-based implementation workflow](./chatgpt-development-workflow.md) and other *git-patch-based workflows* where development environments must be kept in sync, it is appropriate to accept and commit entire patches even if they contain issues; these issues must be handed back to the agent for incremental follow-up patches, before the next implementation phase can commence.

## 5. Review the finished merge request independently

For substantial or medium-to-high-risk changes, a separate reviewer (agent and optionally also human) examines the complete merge request after local review, using the relevant requirements and architecture as context. For localized, low-risk changes, the required human diff review may suffice without another independent agent review. Review follows affected call paths, including interactions beyond modified lines, and checks security, invariants, error handling, concurrency, data consistency, maintainability and missing tests. The repository's [review checklist](../../.agents/skills/engineering-quality/references/review-checklist.md) supports this pass.

Findings identify a concrete condition, its consequence and a suggested correction when possible. Confirmed defects are distinguished from design preferences or speculative enhancements. The human and implementation agent evaluate each finding, apply accepted fixes as reviewable increments, and request another review of the updated revision where necessary. The independent reviewer may also suggest end-to-end workflows or edge cases for the human acceptance test. Reviewer and implementer refer to the **same current commit**, so resolved or outdated comments are not mistaken for live defects.

An independent agent review supplements human review. The human remains responsible for acceptance and merge authorization.

## 6. Perform manual end-to-end acceptance and authorize merge

The human task owner performs a **manual end-to-end acceptance test** before the final merge decision. Depending on the task, this means running or deploying the application locally and exercising the affected behavior from a user's perspective, including relevant error paths and permissions. For infrastructure-only tasks without a meaningful UI journey, the human instead executes an appropriate end-to-end operational or integration workflow. This supplements automated tests and agent review.

The manual test may take place **before or after the merge request has been undergone static code review**. An independent review agent may suggest scenarios, but the human performs the test and records its outcome. Fixes that affect the tested behavior require the relevant scenarios to be checked again. A task with no meaningful manual execution path records that limitation and the alternative acceptance evidence. Acceptance results are retained with the task artifacts when present, or with the change review for lightweight tasks.

Final acceptance examines the complete diff, approved task specification and plan where required, automated verification, manual test results, unresolved risks, deployment or compatibility implications and reconciled steady-state documentation. Approved task artifacts remain available for traceability. The human authorizes the merge into the authoritative repository only after these conditions are satisfied.

## 7. Pre-merge and Merge

Once the task has passed manual end-to-end acceptance and all required reviews are complete, the task can be marked complete in the task backlog as a final commit before merge. The human authorizes the merge into the authoritative repository, ensuring that all review comments have been addressed, all tests have passed, and the task meets the defined acceptance criteria. After merge, approved specifications, plans and acceptance evidence remain in the authoritative repository, and merge-request review history remains available through the code host. Clean up only disposable patch files, temporary workspaces and obsolete feature branches.