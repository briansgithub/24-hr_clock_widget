# Repository Handoff Configuration

Repository-specific policy used with [MASTER_HANDOFF.md](MASTER_HANDOFF.md).

## Git workflow

- Default branch: `main`
- Shared source of truth: `origin/main`
- Branch names: `feature/<purpose>`, `fix/<purpose>`, `chore/<purpose>`
- New work starts from an updated `origin/main`
- Merge strategy: explicit merge commit (`--no-ff`) so branch boundaries remain visible
- Direct feature commits to `main`: prohibited; use a short-lived branch
- Commit, push, PR, merge, stash deletion, and branch deletion require explicit owner authorization unless a standing instruction in the handoff applies

## Required PR-check process

The agent must propose checks before each PR and ask the owner to approve or revise them. Baseline candidates:

- `python scripts/validate_handoffs.py`
- Python changes: `python -m compileall python` and `python python/test_debt_logic.py`
- Android changes: compile/test with the available Android/Gradle environment; if no wrapper/toolchain is available, record that limitation
- UI/rendering changes: targeted manual visual check on affected platform(s)
- Sync/storage changes: targeted conflict, import/export, and failure-path checks

Only owner-approved checks become required for a particular PR. Record commands, results, and deferrals in the active branch handoff.

## Handoff locations

- Active index: `docs/handoffs/README.md`
- Active handoff/history: mirrored branch path under `docs/handoffs/`
- Closed branch archive: `docs/handoffs/archive/`
- Global close ledger: `docs/handoffs/HISTORY.md`
- Templates: `docs/handoffs/templates/`

