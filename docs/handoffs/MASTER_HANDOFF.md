# Master Branch Handoff Protocol

Permanent handoffs live in `docs/handoffs/` and are committed with the repository. This is a lifecycle reference, not required startup reading for every task. Use the routing rules in `AGENTS.md`.

## Non-negotiable rules

1. Preserve unrelated user work and never assume uncommitted changes are disposable.
2. Store durable context in handoffs; obtain transient Git facts from Git when they are relevant.
3. Active handoffs are replacement-style. Per-branch histories and global `HISTORY.md` are append-only.
4. Keep `AGENTS.md` below 3,500 characters, IDE shims below 600, active/archived handoffs below 6,000, each history segment below 12,000, and on-demand policy documents below 20,000 characters.
5. Verify only documentation claims that affect the current action, and distinguish Git-verified facts, owner decisions, unknowns, and recommendations.
6. Keep relevant handoff updates with the commit or PR they describe.
7. Never delete branch documentation; archive it when the branch closes.
8. Never run concurrent agents in one shared working directory; use Git worktrees as described in `MULTI_AGENT.md`.

## When to use this protocol

Consult this file when:

- creating, resuming, handing off, closing, or recovering branch work;
- preparing a PR, merge, release, or archival cleanup;
- changing branch, worktree, stash, or handoff state;
- durable project context is missing or contradictory.

Routine questions, localized edits, and read-only work do not require the full protocol.

## Layout

- `<branch>.md`: concise current operating context
- `<branch>.history.md`: meaningful dated milestones
- `archive/<branch>.md` and `.history.md`: frozen closed records
- `HISTORY.md`: one close entry per branch
- `README.md`: active branch inventory and merge order
- `REPOSITORY.md`: repository-specific workflow, permissions, and check profiles
- `MULTI_AGENT.md`: concurrent-agent isolation with Git worktrees
- `templates/`: starter branch/archive files

Paths mirror branch names, for example `feature/example.md` and `feature/example.history.md`.

## Startup or resumption

1. Begin with `git status --short --branch`.
2. Read the current handoff only when resuming ongoing work, inheriting an unfamiliar branch, or when scope is unclear.
3. Inspect tips, upstreams, merge bases, diffs, stashes, or history only when the intended action depends on them.
4. Verify only handoff claims that affect the next action. Correct stale durable context; do not continually mirror transient Git status into Markdown.
5. Ask the owner only for intent that repository evidence cannot establish.

When agents are actually concurrent, read `MULTI_AGENT.md` and confirm this folder is the agent's dedicated worktree.

## Planning and context budget

Do not create a formal implementation plan for straightforward, localized, or clearly specified work. IDE context, including Android Studio, does not itself justify a plan. Use a plan only when the user requests it or when ambiguity, risk, multiple dependent phases, cross-system coordination, or multi-session continuity makes it useful. Reuse rather than repeatedly restating a plan.

Start investigations narrowly. Before emitting unknown or potentially large files into model context, batch-check them locally with `python scripts/context_probe.py scan <paths>`. Apply the same decision to individual and aggregate `TOTAL` risk. Estimates are heuristic routing signals, not exact tokenizer counts:

- **low:** a full read is normally reasonable;
- **medium:** search or retrieve a targeted chunk first;
- **high:** require a concrete task reason before a full read;
- **extreme:** ask before a full read unless exhaustive review was explicitly requested or is essential to an authorized high-risk operation.

For append-only histories, logs, and reports, search for known identifiers first. Otherwise retrieve the recent end with `context_probe.py tail`, preserving normal order within the chunk, and page backward with `chunk` only when the current evidence requires it. Do not use tail-first reading for source code, configuration, manifests, or other files whose opening structure controls interpretation.

Before optional repository-wide inspection, exhaustive history/log analysis, broad research, or a full build/test matrix that would materially increase token or time cost, explain why it is useful and ask the owner to approve it when a bounded alternative can still make progress. Explicit requests for exhaustive work and verification inherently required for a high-risk change do not need redundant confirmation unless they expand scope.

## Active handoff contents

Keep only durable, non-derivable context:

- **Objective:** purpose, bounded scope, and acceptance criteria
- **Constraints and decisions:** owner decisions and important non-goals
- **Risks or blockers:** unresolved issues that affect continuation
- **Next action:** one immediate safe action
- **Validation checkpoint:** the latest meaningful checks or an explicit reason they are not yet required

Avoid routinely storing exact tips, clean/dirty state, upstream tracking, ahead/behind counts, or complete stash inventories. Git is authoritative for those facts. Include a commit or stash identifier only when it is essential to recovery or a durable milestone.

## Per-branch history

Create an adjacent history for each new branch. Append only meaningful milestones:

- branch creation and intended base;
- a significant implementation or decision checkpoint;
- approved scope or merge-order changes;
- material validation results, PR events, rebases, closure, or recovery actions;
- stash disposition affecting recoverability.

Do not log routine reads, status checks, every prompt, every commit, or facts already available from Git. Never rewrite older entries; append a correction. Keep each history segment below 12,000 characters; freeze older content in numbered segments and continue in a new file. When consulting history, begin with the newest relevant segment or tail chunk and move backward only as needed.

## End of meaningful work

Before the final response:

1. Inspect the resulting diff and Git status once.
2. Run the smallest relevant verification. Broaden it only when failures or risk triggers warrant it.
3. Update the active handoff once if objective, decisions, blockers, risks, validation checkpoint, or next action materially changed.
4. Update `README.md` only if branch inventory, role, or merge order changed.
5. Append history only for a meaningful milestone.
6. Run `python scripts/validate_handoffs.py` once after structural handoff changes or when required by the PR profile. CI may provide the check for ordinary content-only updates.

Do not perform handoff maintenance for read-only work, routine inspection, a no-op session, or an ordinary edit that leaves durable continuation context unchanged. If interrupted during material unfinished work, record the blocker, relevant uncommitted state, and the first safe next action.

## Verification and PR readiness

Use the standing check profile in `REPOSITORY.md`; do not ask the owner to approve the same baseline before every PR. Ask only when choosing between materially different check scopes, when a check is unusually expensive or unavailable, when manual validation requires owner participation, or when deferring a normally required check.

Verification should be proportional:

- documentation or isolated low-risk work: diff review and a directly relevant lightweight check;
- normal behavior changes: targeted tests plus affected lint/type checks when useful;
- shared interfaces, dependencies, build configuration, schemas/migrations, security/authentication, concurrency, persistence, PRs, and releases: broader integration or full-suite checks as appropriate.

For noisy commands, prefer `python scripts/compact_check.py --name <label> -- <command>`. It writes complete failure output to a temporary local log while returning only a bounded tail to the agent; `--quiet` suppresses successful output. Automatically attempt no more than two diagnose/fix/retest cycles for the same failure. If the failure persists or the next check materially expands validation scope, ask before continuing unless the owner already requested broader validation.

Merge-ready means scope is complete, unrelated WIP is absent, the configured checks pass or explicit deferrals are recorded, no known high-severity regression remains, the base is correct, and the handoff reflects residual risks. Never imply that unrun validation passed.

## After merge and pull

1. Update the configured default branch from its remote.
2. Verify the feature tip or merge commit is reachable from the remote default branch.
3. Append the closure milestone to branch history.
4. Move the handoff/history to mirrored `archive/` paths and freeze the handoff to final purpose, landed behavior, validation, disposition, and remaining cleanup.
5. Append one close entry to global `HISTORY.md`.
6. Remove the branch from active `README.md`; update the default-branch handoff only if durable context changed.
7. Delete remote/local branches only after reachability verification, then commit and push the archival cleanup when authorized.
8. Remove obsolete worktrees when their unique work is safely merged or pushed.

Use `git branch -d`; use `-D` only after verified reachability and a known-safe refusal.

## Stashes and recoverability

Delete a stash only after comparing it with reachable work and establishing that all meaningful content is implemented or explicitly abandoned. Never delete a unique stash or wanted commit. Prefer a dedicated worktree over stashing to make room for another agent.

## New branches

1. Update from the remote default branch when authorized and relevant.
2. Create one narrow `feature/`, `fix/`, or `chore/` branch; use a dedicated worktree for actual concurrent work.
3. Create the mirrored handoff/history from templates and record the objective and base milestone.
4. Keep commits purpose-focused and exclude secrets, generated output, IDE-local state, and unrelated formatting.
5. Use the standing validation profile, merge promptly when ready, and complete archival after merge.

## Automated validation

`python scripts/validate_handoffs.py` checks required files, differentiated size limits, links, active/archive pairs, and append-only history when a base ref is available. Use `--quiet` for silent success and `--verbose` only when worktree, branch-tracking, or stash diagnostics are relevant. CI runs the structural validator through `.github/workflows/handoff-validation.yml`.

`scripts/context_probe.py` performs local size/token-risk preflight and bounded recent/backward retrieval. `scripts/compact_check.py` keeps complete command logs out of AI context while preserving failure artifacts for targeted follow-up.

## Compact implementation-agent prompt

> Follow `AGENTS.md`. Start with requested files and concise Git status. Do not plan straightforward work. Size-probe uncertain context; search or read recent append-only chunks before expanding backward. Make the narrowest reliable change, run compact risk-proportional checks, and ask before optional extreme-context, broad-validation, or repeated-failure expansion. Update durable handoff context once only when it materially changes.
