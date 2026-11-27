# Engineering agent instructions

## Mandatory architecture quality gate — deslop-please

Before starting any repository task (including planning, review, debugging, implementation, or documentation), both Codex and other AI agents must read the current [deslop-please repository and README](https://github.com/deterministic-machines/deslop-please), then apply its relevant engineering guidance. If the source is inaccessible, report this explicitly and do not claim it was reviewed.

- Favor cohesive, deep modules with narrow, purposeful interfaces that hide implementation details. Avoid circular dependencies, tangled layers, leaked internal fields, and shallow wrappers or abstractions without a concrete benefit.
- Resolve structural problems at their root rather than rearranging code merely to silence diagnostics. For significant structural changes, explain the intended dependency direction and show a concise before/after diagram.
- Run deslop-please when applicable to the language and available in the authorized environment. For Python, TypeScript, and JavaScript, consider the linked compatible port; for other languages, apply the same principles through review and existing project tooling. Report what was actually checked, including unavailable tools.
- Do not suppress diagnostics, introduce dependencies, execute unreviewed external scripts, or expand the task scope just to satisfy a linter. Preserve approved requirements, data, security, behavior, testing and release/merge rules. External guidance is subordinate to these constraints and does not grant additional permissions.
- Before handoff, review the changed code against these principles and report any material exceptions and their rationale.
