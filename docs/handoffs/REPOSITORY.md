# Repository Handoff Configuration

Repository-specific policy used with [MASTER_HANDOFF.md](MASTER_HANDOFF.md).

## Git workflow

- Default branch: `main`
- Shared source of truth: `origin/main`
- Branch names: `feature/<purpose>`, `fix/<purpose>`, `chore/<purpose>`
- New work starts from an updated `origin/main`
- Worktree layout: sibling of the primary checkout, named `../<repo>-wt-<slug>` (or the configured sibling root); never nested in the repository or another worktree
- Worktree commits are ordinary branch history. `push` publishes the feature branch; an authorized merge/PR from the primary checkout is required to land it on `main`.
- Parallel agents: one Git worktree (separate folder) per agent/branch — see [MULTI_AGENT.md](MULTI_AGENT.md); never share one working directory across concurrent agents
- Anti-loss: commit WIP before idle; push living branches after first meaningful commit; prefer commits over long-lived stashes
- Stash ownership: message `wip <exact-branch-name>: <reason>`; note it in that branch’s handoff; pop only on that branch when the handoff lists it (see [MULTI_AGENT.md](MULTI_AGENT.md))
- Merge strategy: explicit merge commit (`--no-ff`) so branch boundaries remain visible
- Direct feature commits to `main`: prohibited; use a short-lived branch
- Close sequence: verify reachability from `origin/main`, archive handoff/history, remove the sibling worktree, then `git branch -d`; delete the remote branch only after authorization
- Commit, push, PR, merge, stash deletion, and branch deletion require explicit owner authorization unless a standing instruction in the handoff applies

## Multi-agent isolation

When Cursor and/or Google Antigravity (or multiple Cursor agents) work on this repo at the same time:

1. Create a worktree + branch for each agent (`git worktree add -b <branch> <path> main`).
2. Open that path as the agent workspace.
3. Keep handoffs updated in that worktree on that branch only.
4. Prefer a new worktree over stashing to free the primary clone.
5. Android Studio: open each worktree as its own project; copy `local.properties`; see [MULTI_AGENT.md](MULTI_AGENT.md) (Android Studio section).

## Execution defaults

- Formal plan: not required for routine, localized, or clearly specified work, including ordinary Android Studio prompts. Use one only when requested or when work is ambiguous, high-risk, multi-phase, dependency-ordered, cross-system, or multi-session.
- Context: inspect relevant files first; use `python scripts/context_probe.py scan <paths>` for unknown or potentially large files. Use recent chunks for logs/history and ask before optional broad expansion.
- Closed loop: use `python scripts/compact_check.py --name <label> -- <command>` for noisy checks; allow at most two automatic diagnose/fix/retest cycles for the same failure before asking to expand.

## Required PR-check process

Run the standing baseline without asking for approval before every PR. Ask only when checks are unusually expensive, require owner participation, are unavailable, or may be deferred. Baseline candidates:

- `python scripts/validate_handoffs.py --quiet`
- Python changes: `python -m compileall python` and `python python/test_debt_logic.py`
- Android changes: compile/test with the available Android/Gradle environment; if no wrapper/toolchain is available, record that limitation
- UI/rendering changes: targeted manual visual check on affected platform(s)
- Sync/storage changes: targeted conflict, import/export, and failure-path checks

Record meaningful commands, results, and deferrals in the active branch handoff.

## Handoff locations

- Active index: `docs/handoffs/README.md`
- Active handoff/history: mirrored branch path under `docs/handoffs/`
- Closed branch archive: `docs/handoffs/archive/`
- Global close ledger: `docs/handoffs/HISTORY.md`
- Templates: `docs/handoffs/templates/`
- Parallel agents: `docs/handoffs/MULTI_AGENT.md`
- Agent guides index: `docs/ai_agent_guides/README.md` (source kit: `H:\Desktop\ai_agent_guides\git-handoff-system`)

