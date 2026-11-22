# Annotated documentation writing examples

These short excerpts from UFWeb and Cyborg illustrate the editorial style described in [the documentation skill](../SKILL.md). Read the linked surrounding sections, not just the quotations: the example is how a passage fits its audience and document, not a sentence pattern to reproduce. The examples are **stylistic references**, not specifications for ShareWise or evidence that another project's behavior applies here. Preserve the terms and established structure of the document being edited.

## Lead with architectural constraints

**Source:** [UFWeb architecture overview, introduction](https://github.com/frederik-hoeft/ufw-webui/blob/78a039e13289c9c0ae48d0e12239249906ca397d/docs/architecture/architecture-overview.md#L1-L5).

> UFWeb manages UFW through a web application while deliberately leaving firewall authority on the host. Three constraints drive the architecture.

The opening establishes the system's purpose and governing tension before introducing a topology diagram or cataloging components. The following paragraph connects those constraints to concrete architectural choices: the host daemon, the separation of signed mutation authority from REST authentication, and projections over authoritative state. **Emulate the causal progression** from requirement to responsibility to consequence. Do not copy the number of constraints or force every architecture chapter into this exact shape.

## Explain security as separate responsibilities and enforceable guarantees

**Source:** [UFWeb security architecture, two authorization planes](https://github.com/frederik-hoeft/ufw-webui/blob/78a039e13289c9c0ae48d0e12239249906ca397d/docs/architecture/security.md#L15-L31).

> Web authentication and firewall mutation authorization answer different questions.

The section identifies what a JWT establishes, what a browser-signed intent establishes, and which component validates each. It then explains the outcome of requiring both checks, including what happens when either is missing. That is more useful than listing filters, signature services, and endpoints. **Emulate the explicit trust boundary and consequence**, including a precise statement of what compromise or verification does *not* guarantee where necessary; avoid generic assurances that a design is “secure.”

## Separate similar concepts before detailing their mechanics

**Source:** [Cyborg transaction architecture, execution model](https://github.com/frederik-hoeft/cyborg/blob/c78e618ca515a0a8c13d7874b79d5ea67538d068/docs/architecture/transactions.md#L9-L68).

> Keeping these dimensions separate prevents execution identity, service lifetimes, and environment inheritance from implicitly determining transaction semantics.

The introduction explains that execution ownership, transaction ancestry, DI lifetimes, and environment topology overlap without being interchangeable. The compact comparison table makes their different responsibilities visible, and the following subsections explain each relationship with a small diagram and its consequence. **Emulate the progression from conceptual distinction to comparison to individual explanations.** A table is appropriate here because it clarifies parallel dimensions, not because every type or method needs a row.

## Orient readers before offering a capability inventory

**Source:** [Cyborg README, overview](https://github.com/frederik-hoeft/cyborg/blob/c78e618ca515a0a8c13d7874b79d5ea67538d068/README.md#L1-L35).

> Cyborg treats orchestration as composition rather than scripting.

The opening identifies the product, its execution model, the deployment form, and a concrete included use case. The overview then gives readers a unifying concept before its scannable capabilities and setup sections. **Emulate the audience-oriented layering**: what it is, what distinguishes it, what it can do, and how to begin. The feature list is useful in a README because readers are evaluating and navigating the project; it would be a poor substitute for an architectural explanation of how those capabilities work.

## State operational assumptions before commands and verify outcomes

**Source:** [UFWeb rootless Docker runbook, preparation and verification](https://github.com/frederik-hoeft/ufw-webui/blob/78a039e13289c9c0ae48d0e12239249906ca397d/docs/deployment/rootless-docker.md#L1-L28) and [deployment verification](https://github.com/frederik-hoeft/ufw-webui/blob/78a039e13289c9c0ae48d0e12239249906ca397d/docs/deployment/rootless-docker.md#L194-L222).

> The key mapping rule is simple: supplemental container GID `0` maps to the rootless Docker user's primary host group.

The guide starts by establishing which runtime mode it covers and the permission-mapping rule that makes later commands meaningful. Steps show the commands to run and the assumptions they depend on; the final checks include an expected `401` and diagnostics for a missing socket mount, rather than declaring success merely because containers started. **Emulate the task-oriented sequence:** prerequisites and safety constraints, exact actions, observable results, and diagnosis. Imperatives and commands are appropriate in a runbook, but not as the default voice of architecture documentation.

## How to use these references

For a substantial writing task, select the one or two examples that match the document's purpose, read the linked passage in context, then draft using the current project's own facts and terminology. During review, ask whether each paragraph explains a responsibility, relationship, guarantee, constraint, or action that readers genuinely need. Do not imitate a source's length, section headings, product claims, or technology choices simply because its prose is effective. For a small factual correction, follow the skill's normal editing and validation workflow without loading this file.
