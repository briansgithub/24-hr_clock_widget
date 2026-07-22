# AI Agent Guides (installed)

This repository uses the portable **Git Handoff System** from the external guide kit at `H:\Desktop\ai_agent_guides\git-handoff-system`.

## Agent entry points

| File | Audience |
|---|---|
| [AGENTS.md](../../AGENTS.md) | Vendor-neutral entry for all agents and IDEs |
| [GEMINI.md](../../GEMINI.md) | Google/Gemini agents (handoff protocol + coding directives) |
| [.cursor/rules/handoff-maintenance.mdc](../../.cursor/rules/handoff-maintenance.mdc) | Cursor always-applied handoff rule |

## Handoff system (installed paths)

```text
AGENTS.md
GEMINI.md
.cursor/rules/handoff-maintenance.mdc
.github/workflows/handoff-validation.yml
docs/handoffs/
scripts/validate_handoffs.py
```

## Startup checklist (every agent session)

1. Read `AGENTS.md` → `docs/handoffs/MASTER_HANDOFF.md` → `REPOSITORY.md` → `README.md` (index).
2. Read current branch handoff + `.history.md`.
3. If parallel agents may run: read `docs/handoffs/MULTI_AGENT.md`; confirm dedicated worktree.
4. Verify handoff claims against `git status`, branch tips, and diff.
5. After changes: update handoff/index, append meaningful history, run `python scripts/validate_handoffs.py`.

## Upstream kit

To refresh templates or protocol from the source kit, compare:

- External: `H:\Desktop\ai_agent_guides\git-handoff-system\`
- This repo: project root (paths above)

Preserve repository-specific content in `docs/handoffs/REPOSITORY.md`, `docs/handoffs/README.md`, and active branch handoffs. Do not copy another repository's archive data.

See [file_purposes.md](file_purposes.md) for a file-by-file map of the installed framework.
