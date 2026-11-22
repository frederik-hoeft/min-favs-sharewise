---
name: documentation
description: Write and revise software documentation that is accurate, reader-led, concise and coherent. Explain system responsibilities, contracts and behavior in natural technical prose; preserve the distinction between implemented architecture and proposals.
---

# Documentation

Use this skill for **all documentation work**, including architecture, design proposals, reference material, READMEs, developer guides, task plans and substantial documentation comments.

The goal is to give the intended reader a dependable mental model or the instructions needed to complete a task. Good documentation reads as an explanation of the subject, not an inventory of source files, a transcript of implementation work or a collection of warnings. **Be concise without omitting the relationships, invariants and consequences that make the system understandable.**

## The desired writing style

### Lead the reader through the system

Start with the question a reader needs answered and the central idea that answers it. In architecture documentation, explain what the subsystem is responsible for, how it fits among its neighbors, and what a consumer can rely on. Develop the explanation in a natural progression: concepts and ownership, interactions and lifecycle, then important guarantees, limits and failure behavior. This is a narrative guide, not a mandatory heading template.

Give each paragraph a job. State one meaningful idea, provide the mechanics needed to understand it, and explain its consequence when that connection is not obvious. Make the relationships between components explicit instead of expecting readers to infer architecture from a list of classes or API members. Group related facts; avoid one-sentence paragraphs for details that belong in the same explanation.

For example, prefer a connected explanation:

> The ingress verifies a caller's credential before dispatch. The application operation then receives an immutable view of the accepted identity through its own scope, so business services do not depend on the HTTP request. Absent credentials permit anonymous operations whereas supplied invalid credentials are rejected at ingress.

Over an implementation inventory:

> `BearerFilter` validates tokens. `IdentityInitializer` sets the identity. `IdentityContext` exposes the user. Invalid tokens are rejected. The system does not use request scope in services.

Use concrete types where they identify an important published contract or clarify how the architecture works. Otherwise, explain responsibilities and relationships first; the code supplies exhaustive symbol-level detail.

### Write precise, natural, declarative prose

Prefer direct sentences with clear subjects and verbs: *the dispatcher creates a scope*, *the provider verifies evidence*, *the domain context decides permission*. In architecture prose, describe **current responsibilities and guarantees**, not instructions to a hypothetical implementer. Reserve imperatives for procedures, plans, checklists and explicitly stated implementation requirements.

Use present tense and a steady-state viewpoint. Avoid change-log language such as "now", "new", "previously" or "was changed to" unless the change itself matters to a migration or compatibility contract. A document should still read naturally long after its originating PR has merged.

Use precise terminology consistently. Introduce an abstraction once, then use the same term rather than inventing near-synonyms. Explain an important distinction at the point where readers need it. Prefer positive contracts to repetitive defensive statements: describe what a component owns and guarantees, and state an exclusion only when overlooking it would cause a real misunderstanding.

Avoid promotional language, filler, vague assertions and redundant transitions. Do not repeatedly announce that a feature is "not an authorization engine", "not implemented yet" or "not a new scope" when one clear explanation already establishes the boundary. Use **bold** sparingly to make a central distinction findable, not to emphasize every concept.

Concise does not mean terse. A subtle failure rule, trust boundary, ordering constraint or design tradeoff deserves enough explanation to be usable. Omit details whose only justification is that they appeared in the implementation diff.

### Use structure to support the narrative

Organize sections by meaningful concepts, responsibilities and reader questions, not by one heading per class or a chronological list of implementation steps. Prefer paragraphs for causal explanations and interactions. Use lists for genuinely separate items, tables for comparisons or compact reference material, and diagrams for relationships or flows that are clearer visually. A table or diagram supports the prose; it must not be the only place where the essential contract can be understood.

Examples should illuminate a non-obvious interaction, lifecycle, configuration or invariant. Keep them small and accurate. Do not add a diagram, extensive example or exhaustive case list merely to demonstrate completeness.

### Annotated reference examples

For substantial architecture writing, README introductions, runbooks, or a deliberate editorial pass, consult [writing examples](references/writing-examples.md). The short excerpts show effective structure and emphasis in real project documentation; use them to calibrate prose, not as fixed templates or as technical authority for the current task. Routine factual edits do not require rereading the examples.

## Accuracy, status and documentation ownership

### Distinguish what exists from what is planned

Ground statements in the actual implementation and approved design. An architecture document describes **implemented behavior**; a proposal describes a **proposed contract**; a plan describes **work and acceptance criteria**. Make status explicit near the start of a proposed or transitional section, then write naturally within that context rather than repeating the disclaimer in every paragraph. Never present intended behavior as verified runtime behavior or infer a guarantee from tests that do not exercise it.

Record important design decisions, including their tradeoffs and consequences, but avoid narrating review history in steady-state architecture docs. A temporary implementation limitation belongs in architecture documentation only when it changes what consumers can rely on or what future work must accommodate.

### Give each contract one authoritative home

Place the full explanation in the document where readers would expect to find it. Other documents should summarize only the context they need and link to that authoritative explanation. Extend an existing relevant section rather than creating a parallel section, tiny document or detached appendix. Avoid spreading subtly different versions of one rule across architecture, stack proposals and task plans.

When a behavior changes, check **every document that depends on that contract**, including proposals for future consumers. Search for old terminology and paraphrases of superseded rules, not just exact symbol names. A valid old statement can become misleading after the implementation changes, even when every Markdown link still resolves. Reconcile the documents rather than adding a caveat that merely coexists with contradictory text.

Keep unrelated, accurate documentation intact. Match the surrounding document's vocabulary, detail, organization and tone. Do not perform broad stylistic rewrites as incidental cleanup; a deliberate whole-document redesign is a separate, reviewable task.

## Fit the document to its purpose

- **Architecture:** Explain concepts, responsibilities, boundaries, data/control flow, lifecycle, invariants and meaningful failure semantics. Published interfaces may be named, but do not turn the document into an API catalog or a set of implementation instructions.
- **Design proposals and decisions:** State the problem, chosen or proposed model, material alternatives, consequences and unresolved questions. Make the proposal's status unambiguous and link to implemented architecture where relevant.
- **Reference documentation:** Optimize for accurate lookup and completeness of supported APIs, configuration and behavior. Tables and structured lists are appropriate; link to conceptual explanations instead of duplicating them.
- **Developer and user guides:** Organize around tasks and expected outcomes. Provide verified prerequisites, commands, examples, failure diagnostics and recovery steps where relevant. Imperatives belong here.
- **READMEs:** Orient readers quickly: purpose, capabilities, setup and navigation to deeper material. Avoid embedding the entire architecture in the landing page.
- **Plans and backlogs:** Define reviewable deliverables, dependencies, decisions, constraints and acceptance evidence. Avoid restating architecture and specification details in every task entry.

## Editing and review workflow

1. **Establish the source of truth.** Read the affected code, current docs and adjacent sections before drafting. Identify the audience, document type and authoritative home. For an implementation-driven update, understand the actual behavior and relevant tests first.
2. **Identify the contract delta.** Write down what changed in responsibility, lifecycle, inputs/outputs, ownership, failure behavior or supported status. Separate the change that readers need to know from incidental file and symbol changes.
3. **Make the smallest coherent edit.** Integrate the new explanation where it belongs, preserving a readable conceptual progression. Rewrite the surrounding paragraph when an appended exception would be awkward; avoid wholesale rewriting unrelated sections.
4. **Read for coherence, not just correctness.** Read the affected section straight through without looking at the diff. Check that paragraphs connect, headings help navigation, terms stay consistent, examples earn their space, and no sentence merely repeats an earlier warning. If the writing sounds like a PR summary or an implementation checklist, rewrite it as an explanation of the system.
5. **Check all dependent descriptions.** Search the documentation set for the changed concept and its former wording, especially future designs that assume the old behavior. Correct contradictions and distinguish implemented guarantees from proposed behavior.
6. **Validate and inspect the diff.** Check examples and references against code, verify local links and headings, inspect formatting and run `git diff --check` where available. Confirm the diff contains no unrelated rewrites or misleading claims of test coverage or deployment acceptance.

## Markdown conventions

- Use UTF-8 with LF line endings, a final newline and no trailing whitespace. Do not use trailing-space hard line breaks.
- Keep **one logical paragraph per source line** without manual column wrapping. Separate paragraphs with blank lines; preserve syntax-sensitive whitespace in tables, lists, fences and diagrams.
- Use `-` for unordered lists and four spaces for each nested-list indentation level. Prefer prose unless enumeration makes the material easier to follow.
- Use fenced code blocks with an appropriate language (`java`, `typescript`, `bash`, `json`, `mermaid`, or `text` for plain text). Do not use untyped or indentation-only code fences.
- Keep heading levels meaningful and links relative where appropriate. Preserve existing heading targets and link compatibility when practical.
- Use diagrams and tables only when they materially clarify the subject, and introduce their purpose in the surrounding prose.

## Final editorial test

A reader unfamiliar with the PR should be able to answer: **What does this part of the system do? Who owns each responsibility? How do the parts interact? What can callers rely on? What remains proposed or outside the contract?** The answers should follow naturally from the prose without reconstructing them from type names, scattered caveats or implementation history.
