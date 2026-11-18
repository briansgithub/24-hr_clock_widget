# Agent Defaults

Use the least context, planning, and validation that can complete the task reliably.

## Work

- Start with the request and relevant files. Before editing, run `git status --short --branch`; inspect broader Git state only when needed and preserve unrelated changes.
- Do not formally plan clear, localized work, including routine Android Studio prompts. Plan only when requested or when work is ambiguous, high-risk, multi-phase, dependency-ordered, cross-system, or multi-session. Reuse plans; update them only when the approach changes materially.

## Context

- Before loading unknown or potentially large files, batch-run `python scripts/context_probe.py scan <paths>`. Apply individual and `TOTAL` risk: low may be read fully; medium search/chunk first; high needs a task-specific reason for a full read; extreme needs user confirmation unless exhaustive review was requested or essential to authorized high-risk work.
- For append-only logs, histories, and reports, search known terms first; otherwise use `context_probe.py tail`, then `chunk` backward only as needed. Preserve order within chunks. For source, configuration, manifests, and structured documents, use symbols/search/line ranges—never tail-first reading.
- Ask before optional repository-wide inspection, exhaustive logs/history/research, or other major context expansion; state why and the bounded alternative.

## Verification

- Run the smallest relevant check. Broaden only for failure, unclear impact, shared interfaces, dependencies/build configuration, schemas/migrations, security/authentication, concurrency, persistence, PRs, or releases.
- For noisy commands use `python scripts/compact_check.py --name <label> -- <command>`; use `--quiet` for silent success. Never claim an unrun check passed or rerun a successful check after no relevant change.
- Allow at most two diagnose/fix/retest cycles for the same failure; then summarize and ask before expanding unless broader validation was requested. Use the project check profile in `docs/handoffs/REPOSITORY.md` and prefer CI for full matrices.

## Handoffs

- Read the active handoff only when resuming/inheriting work or scope is unclear; read only needed, newest history. Consult relevant sections of `MASTER_HANDOFF.md` for lifecycle/recovery, `REPOSITORY.md` for policy/check profiles, and `MULTI_AGENT.md` only for actual concurrency. Concurrent agents require separate worktrees.
- Worktrees are sibling folders beside the primary checkout, never nested; each is a normal Git branch whose commits stay in Git history. Push publishes that branch; only an authorized merge/PR brings it into the default branch.
- Close work from the primary/default checkout: verify the merge is reachable, archive the handoff, then remove the sibling worktree and use `git branch -d`; delete the remote branch only after authorization. Stop on dirty/unmerged work; never force-delete or overwrite another agent's work.
- Update a handoff once only when durable objective, scope, decisions, blockers, risks, meaningful validation, or next action changed—not for read-only, routine, no-op, or context-neutral work. Update the index only for branch inventory/role/order; append history only for milestones.
- Run `python scripts/validate_handoffs.py --quiet` after structural handoff changes and when configured for PRs. Follow `MASTER_HANDOFF.md` for destructive/branch-closing operations; never delete branch documentation.
