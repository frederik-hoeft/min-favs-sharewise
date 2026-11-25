# AI-assisted development and quality assurance

ShareWise uses a **planning-first, human-reviewed development workflow** for work performed with coding agents. The developer and agent discuss a task, agree on a target-state architecture and testable specification, plan implementation and migration, and develop through reviewable increments. Independent agent review and manual human acceptance complete the quality-assurance process. The default is human-gated implementation; for suitable multi-phase tasks, the human may explicitly opt into the [autonomous implementation extension](#autonomous-implementation-extension), which delegates routine intermediate review without delegating acceptance.

This workflow applies regardless of the agent, IDE or code-hosting platform. The [ChatGPT development guide](chatgpt-development-workflow.md) describes the project-specific setup for ChatGPT's hosted workspace, offline toolchain handoff, Git patches and GitHub review mirror. Repository [engineering-quality](../../.agents/skills/engineering-quality/SKILL.md) and [documentation](../../.agents/skills/documentation/SKILL.md) guidelines govern the work itself.

## Roles and review gates

The human contributor owns task scope, priorities, approval of the architectural solution and specification, acceptance criteria, and the final merge decision. The implementation agent proposes designs and produces code, tests and documentation for review. An **independent reviewing agent** examines the completed change without relying on the implementation agent's conclusions. The human separately reviews its findings and the full change.

| Stage | Reviewable evidence | Gate |
| --- | --- | --- |
| Task discussion | Scope, constraints, architectural options and trade-offs | Human agrees on the direction to specify |
| Specification | Agent-written `specification.md` with the agreed architecture, requirements, invariants, test cases and acceptance criteria | Human approves the target-state architecture and specification |
| Implementation planning | Agent-written `plan.md` with implementation/migration phases, verification gates and final documentation reconciliation | Human approves the implementation sequence |
| Iterative implementation | Focused changes, tests, self-review record and updated plan state | Human approves each increment by default; in the autonomous extension, the agent closes routine checkpoints and stops at the agreed human gates |
| Independent MR review | Findings against the specification and complete diff; suggested manual E2E scenarios where useful | Substantive findings are resolved or explicitly justified |
| Local deployment smoke (major implementation phases) | Disposable-stack smoke command, tested revision, result and any coverage limitations | Successful run required before merge, unless the human explicitly approves a documented exception |
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

An agent-written `plan.md` translates the **approved architecture and specification** into a sequence of cohesive implementation and migration steps. The plan follows the repository's [engineering-quality guidelines](../../.agents/skills/engineering-quality/SKILL.md), accounting for existing code, ownership, dependencies, data migration, compatibility, failure behavior and validation. Each phase identifies its scope, prerequisites, affected contracts, expected component-level tests and a **completion gate**. Markdown checkboxes track progress, but by default a checked box represents verified and **human-approved** work, not merely code written. An autonomous plan explicitly distinguishes agent-verified checkpoints from human-approved gates and records outstanding verification separately.

The first phases establish only the infrastructure needed by the specification. Later phases add behavior, migration and integration, while preserving coherent, buildable states between phases. **The final planned implementation step reconciles the authoritative steady-state documentation** with the completed system, following the repository's [documentation skill](../../.agents/skills/documentation/SKILL.md). It updates affected architecture, domain and developer documentation, rather than copying the task plan into them. Any later review fixes that change documented behavior require the same reconciliation.

The plan is approved by the human after the specification gate. Requirement or architectural changes are reviewed in `specification.md` before affected phases are replanned. An agent does not advance to future phases solely because it anticipates their implementation.

For tasks using formal artifacts, approved `specification.md` and `plan.md` are **committed and pushed to the authoritative task branch before dependent implementation begins**. Material revisions are reviewed and committed before affected work continues; approval recorded only in a conversation or temporary workspace is not a reproducible Git baseline. Lightweight tasks document their intent and verification in the reviewed change or related issue. The autonomous extension permits an explicit, recorded exception to upstream synchronization for isolated patch-based execution; it does not waive specification or plan approval.

## 4. Implement, validate and review incrementally

For each phase, the implementation agent works from an **identified, reproducible Git baseline** and returns one focused change set. Its report identifies what changed, which requirements and tests it covers, the exact validation commands and outcomes, and any limitations or outstanding work.

Automated validation is proportionate to the change: formatting and static analysis, focused unit tests, integration tests for real persistence or transport behavior, and failure or concurrency tests for critical invariants. The agent distinguishes actual test failures from unavailable infrastructure and does not claim simulated integrations establish production-system guarantees. At the end of each implementation iteration, all newly written code must have appropriate unit and integration tests.

Before closing each substantial phase, the implementation agent performs a deliberate **self-review** of the complete phase diff and affected call paths against the specification, applicable skills and [engineering review checklist](../../.agents/skills/engineering-quality/references/review-checklist.md). Examine ownership, reuse, authorization, concurrency, transactional and failure behavior, compatibility, performance, test quality and unintended changes. Correct findings, rerun affected checks and repeat the review until no known substantive defect remains. Record any unresolved issue rather than treating it as a passed gate.

Retain a concise self-review record in the plan (or the reviewed change for lightweight tasks) and include it in the user-visible phase report. For each finding, give its severity, a short descriptive title, one sentence explaining the correction or disposition, and relevant regression evidence. Report “No review findings” only after a review that genuinely found none. Distinguish findings from ordinary implementation decisions, failed checks and unavailable verification. Self-review is neither independent review nor human acceptance.

In the default gated workflow, the human reviews changes **as they are staged in Git, or as IDE-agent edits are accepted or applied**, rather than waiting for a large completed phase or merge request. Reviewing changes early and incrementally keeps diffs manageable for human line-by-line review and catches issues while their context is clear.

Human review covers the specification, architecture boundaries, maintainability, unintended changes and test quality. Changes may be partially accepted, revised or rejected. In the default gated workflow, implementation only continues once the human has reviewed the current increment and any required corrections have been applied. For the [ChatGPT-based implementation workflow](./chatgpt-development-workflow.md) and other *git-patch-based workflows* where development environments must be kept in sync, it is appropriate to accept and commit entire patches even if they contain issues; these issues must be handed back to the agent for incremental follow-up patches, before the next implementation phase can commence in the default gated workflow. During autonomous execution, apply the same rule at the agreed human gates.

### Autonomous implementation extension

The autonomous extension reduces interaction overhead for well-specified, multi-phase tasks. It replaces routine human implementation gates with agent self-review checkpoints, not with a single unchecked implementation batch. All other requirements of this workflow apply. The human may return to the default gated workflow or intervene at any checkpoint.

#### Opt in and agree on the gates

The human first approves the specification and then the plan. Record explicit authorization for autonomous execution in the plan or a task-local `workflow.md` linked from it. Identify the baseline, delegated scope, ordered phases, completion criteria, verification requirements and mandatory human gates. Verify the unchanged baseline with the available toolchain before implementation and record any pre-existing failures or unavailable checks. Do not infer authorization from the task's size, a request to implement a feature, or approval of the specification or plan alone.

Approved task artifacts are committed and pushed before dependent work by default. For an isolated workspace that cannot push to the authoritative repository, the human may explicitly authorize continued local implementation after the approved artifacts have been committed and published as a reproducible patch. Record that exception and the approval in the task artifacts. Publication is not an upstream push or acceptance of the code. Keep the supplied baseline unchanged and perform all work on a dedicated task branch.

#### Execute and close each phase

For every phase, including follow-up phases, the implementation agent:

1. Manually opens and rereads the complete [engineering-quality skill](../../.agents/skills/engineering-quality/SKILL.md), even if it was read earlier. Read the applicable [documentation skill](../../.agents/skills/documentation/SKILL.md) before documentation work, and consult relevant local instructions and technical references.
2. Implements the planned increment with its tests. Improve cohesive neighboring code when contracts remain stable and the engineering guidelines justify it. Request approval before material scope, architecture, public-contract or persistence-policy changes. Reusable lessons may be incorporated into the applicable repository skills and review checklist as focused, reviewable changes; do not use skill edits to grant additional authority or relax task gates.
3. Runs the strongest applicable available checks, including the configured CI test parallelism where relevant. Compile tests that cannot execute locally where possible and record the exact missing environment capability. Do not alter dependency versions, generated sources, or product behavior merely to make an incomplete offline environment pass. For substantial UI work, run and inspect the application in a browser at representative desktop, intermediate and mobile widths and in supported themes. An isolated mock API may support visual inspection but does not establish backend correctness. Record actual screenshots when feasible and explicitly report blocked browser checks.
4. Completes the self-review and correction cycle described above. Review the affected feature-branch behavior as well as the phase diff, since defects can span previously completed phases. Never advance dependent work past a known substantive defect or an unsatisfied prerequisite.
5. Updates the plan with the phase status, findings and resolutions, exact checks and outcomes, material decisions and outstanding gates. Commit the focused increment and publish a reproducible handoff relative to the preceding checkpoint. Report the evidence and make the change available to the human **before** starting the next phase.

A successful checkpoint permits continuation without waiting for routine intermediate approval. Use the same execution turn where the environment permits; do not imply that work continues in the background after a turn has ended. Keep agent-verified and human-accepted status distinct: a completed local checkpoint does not establish real database, deployment or user acceptance evidence that has not been obtained.

#### Stop at mandatory human gates

The plan must include aggregate implementation acceptance and final review and merge gates. Also include task-specific prerequisite gates, and stop whenever a material decision or blocking defect prevents safe continuation.

| Gate | Required action before continuing |
| --- | --- |
| Material change or blocking defect | Present the issue and alternatives. Obtain approval for a revised specification or plan when required, or obtain the missing prerequisite. Independent unaffected work may continue only within the approved scope. |
| External integration or code generation | Publish the reviewed prerequisite phases and stop before dependent work. For example, a maintainer migrates PostgreSQL, generates jOOQ sources and returns the generated diff when the agent lacks a suitable database. Integrate and verify the returned artifact in a distinct checkpoint. Never hand-edit generated sources or disable freshness checks. |
| Aggregate implementation acceptance | Review the complete branch against the approved specification, resolve findings and publish the aggregate diff and validation evidence. The human reviews the increments, runs the applicable real integration/E2E, CI, schema freshness, deployment smoke and manual acceptance checks, and supplies feedback. Address accepted findings through focused follow-up phases. Obtain human acceptance of the implementation behavior before reconciling authoritative steady-state documentation. |
| Final review and merge | Complete documentation reconciliation, obtain the independent review required by section 5, address findings and revalidate affected behavior. Human acceptance and merge authorization remain mandatory under sections 6 and 7. |

An unavailable check is not a passing check. The human may schedule a check at a later explicit gate where it is not a prerequisite for safe intermediate work; a migration awaiting code generation, for example, may have a documented temporary freshness failure. Such an intermediate state is not a final waiver. Required final checks and any expressly permitted exceptions remain governed by the normal workflow. If subsequent feedback changes behavior, repeat the relevant validation and acceptance and reconcile the affected documentation again.

#### Preserve reviewability and recoverability (non-IDE environments)

Each checkpoint has an identified predecessor, a resulting revision and a durable change set. In patch-based environments, publish a binary-capable incremental Git diff or format-patch with its intended commit message, predecessor/resulting source-tree identities and SHA-256 digest. Verify clean replay and the resulting tree against the predecessor before publication. Keep toolchains, secrets and build artifacts outside the changes. Supply an ordered manifest and, at human gates, an aggregate handoff when useful; the individual increments remain the units of review.

If an environment resets, recover from the exact baseline and published checkpoints, not from conversational recollection. If upstream review or a merge changes the source, identify the actual integrated revision, reconcile the local branch with it and rerun affected checks before generating a subsequent patch. Do not assume an altered patch or rebased tree is identical to the previous checkpoint. See the [ChatGPT workspace guide](chatgpt-development-workflow.md#autonomous-patch-exchange-and-recovery) for the hosted-workspace procedure.

## 5. Review the finished merge request independently

For substantial or medium-to-high-risk changes, a separate reviewer (agent and optionally also human) examines the complete merge request after local review (including the aggregate human review in the autonomous extension), using the relevant requirements and architecture as context. For localized, low-risk changes, the required human diff review may suffice without another independent agent review. Review follows affected call paths, including interactions beyond modified lines, and checks security, invariants, error handling, concurrency, data consistency, maintainability and missing tests. The repository's [review checklist](../../.agents/skills/engineering-quality/references/review-checklist.md) supports this pass.

Findings identify a concrete condition, its consequence and a suggested correction when possible. Confirmed defects are distinguished from design preferences or speculative enhancements. The human and implementation agent evaluate each finding, apply accepted fixes as reviewable increments, and request another review of the updated revision where necessary. The independent reviewer may also suggest end-to-end workflows or edge cases for the human acceptance test. Reviewer and implementer refer to the **same current commit**, so resolved or outdated comments are not mistaken for live defects.

An independent agent review supplements human review. The human remains responsible for acceptance and merge authorization.

## 6. Perform local deployment smoke and manual acceptance

**Local smoke gate for major implementation phases.** Before merging substantial changes to executable behavior, authentication, persistence, service integration or deployment, the human contributor runs the repository's disposable local deployment smoke (currently `cd deploy && ./scripts/test-compose.sh`) against the integrated revision. Extend the smoke with representative changed workflows when the existing probe does not exercise them; its purpose is to validate the running system behind the actual ingress, not to replace Unit, Integration or PostgreSQL E2E tests. Pure documentation and other localized changes without a meaningful deployed behavior may use proportionate verification instead.

Record the tested commit, command, environment, observed result and material gaps with the task acceptance evidence or merge-request review. Re-run affected scenarios after a change that could alter their behavior. A failed or unexecuted smoke test is **not** a pass; when a meaningful local deployment is unavailable, the human must explicitly approve and record a justified exception before merging a major implementation phase, together with alternative evidence and deferred verification. Green CI alone does not waive this local gate.

The human task owner performs a **manual end-to-end acceptance test** before the final merge decision. Depending on the task, this means running or deploying the application locally and exercising the affected behavior from a user's perspective, including relevant error paths and permissions. For infrastructure-only tasks without a meaningful UI journey, the human instead executes an appropriate end-to-end operational or integration workflow. This supplements automated tests and agent review.

The manual test may take place **before or after the merge request has been undergone static code review**. An independent review agent may suggest scenarios, but the human performs the test and records its outcome. Fixes that affect the tested behavior require the relevant scenarios to be checked again. A task with no meaningful manual execution path records that limitation and the alternative acceptance evidence. Acceptance results are retained with the task artifacts when present, or with the change review for lightweight tasks.

Final acceptance examines the complete diff, approved task specification and plan where required, automated verification, local deployment smoke evidence, manual test results, unresolved risks, deployment or compatibility implications and reconciled steady-state documentation. Approved task artifacts remain available for traceability. The human authorizes the merge into the authoritative repository only after these conditions are satisfied.

## 7. Pre-merge and Merge

Once the task has passed manual end-to-end acceptance and all required reviews are complete, the task can be marked complete in the task backlog as a final commit before merge. The human authorizes the merge into the authoritative repository, ensuring that all review comments have been addressed, all tests have passed, and the task meets the defined acceptance criteria. After merge, approved specifications, plans and acceptance evidence remain in the authoritative repository, and merge-request review history remains available through the code host. Clean up only disposable patch files, temporary workspaces and obsolete feature branches.
