# AI Agent Guides (installed)

This repository uses the portable Git Handoff System from `H:\Desktop\ai_agent_guides\git-handoff-system`.

## Entry points

- [AGENTS.md](../../AGENTS.md): authoritative compact procedure
- [GEMINI.md](../../GEMINI.md): Gemini entry point
- [.cursor/rules/handoff-maintenance.mdc](../../.cursor/rules/handoff-maintenance.mdc): Cursor shim

## Installed support

```text
AGENTS.md
GEMINI.md
.cursor/rules/handoff-maintenance.mdc
.github/workflows/handoff-validation.yml
docs/handoffs/
scripts/context_probe.py
scripts/compact_check.py
scripts/validate_handoffs.py
```

Follow `AGENTS.md`. Load handoff/lifecycle documents only when resuming work, scope is unclear, branch/worktree state changes, a PR is being prepared, or agents are actually concurrent. Use `context_probe.py` for large candidates and `compact_check.py` for noisy commands.

Preserve repository-specific content in `docs/handoffs/REPOSITORY.md`, `docs/handoffs/README.md`, and active branch handoffs. Do not copy another repository's archive data.

Upstream reference: `H:\Desktop\ai_agent_guides\git-handoff-system\`.
