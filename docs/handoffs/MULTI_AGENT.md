# Multi-Agent Parallel Work (Git Worktrees)

Use this when two or more agents (Cursor, Google Antigravity/Gemini, or mixed) work on the same repository at once.

## Rule

**Do not run parallel agents in one shared working directory.**  
One folder + one checkout forces branch switching, stashing, file overwrites, lock conflicts, and stale agent context.

**Isolate each agent with its own Git worktree:** a separate folder checked out to that agent’s branch, linked to the same underlying `.git` database.

## Why worktrees

| Shared single folder | One worktree per agent |
|---|---|
| Agents fight over the same files | Each agent edits only its checkout |
| Constant `checkout` / stash | No branch swapping to “make room” |
| Dirty WIP blocks other agents | Dirty trees stay local to each worktree |
| Context drifts after switches | Each IDE window stays pinned to one branch |

## Standard workflow

1. Update the default branch in the primary clone:
   ```bash
   git checkout main
   git pull origin main
   ```
2. Create one worktree per agent/task (new branch):
   ```bash
   git worktree add -b feature/<purpose> <path-to-new-folder> main
   ```
   Example:
   ```bash
   git worktree add -b feature/foo ../repo-wt-foo main
   ```
3. Open **that folder** as the agent’s workspace in Cursor or Antigravity (not the primary clone unless this agent owns it).
4. In that worktree: create the mirrored handoff + `.history.md`, implement, validate, commit on that branch only.
5. When finished: push/PR from that branch, then after merge follow the normal archive + branch-delete protocol.
6. Remove the worktree when done:
   ```bash
   git worktree remove <path-to-new-folder>
   ```

List worktrees anytime:

```bash
git worktree list
```

## Handoff rules with worktrees

- Handoffs remain in `docs/handoffs/` and are committed **on the branch that owns the work**.
- Each agent updates only its own branch handoff/history in its worktree.
- The active index (`README.md`) on a branch should list that branch (and `main` when present); do not expect every sibling worktree’s branch files to exist in every checkout until those branches merge.
- `python scripts/validate_handoffs.py` must pass **in the worktree where the agent is working**. It requires:
  - every branch listed in that checkout’s active index;
  - the currently checked-out branch;
  - size limits, links, and archive pairs.
- It does **not** fail solely because another local branch is checked out in a different worktree and its handoff files are not present here yet.

## Cursor and Antigravity

- **Cursor:** File → Open Folder on the worktree path; one chat/agent per worktree folder.
- **Google Antigravity / Gemini:** point the agent at the worktree folder (Antigravity often creates worktrees under its own path—treat that folder as the workspace and keep handoffs updated there).
- Keep `AGENTS.md` / `GEMINI.md` / `.cursor/rules/` in the repo so every worktree inherits the same protocol after checkout.

## Android Studio (Android apps)

Git allows only one checkout of a given branch. If a worktree already has `feature/foo`, Android Studio’s branch switcher in another folder will fail with “already used by worktree…”.

**Smoother device-run workflow:**

1. Treat each worktree as a **separate Android Studio project** — `File → Open` the worktree folder (e.g. `…/repo-wt-foo`), do not open the primary clone and try to check out that branch.
2. After creating a worktree, copy gitignored local machine files the build needs (especially `local.properties` with the Android SDK path) from the primary clone into the new folder.
3. Expect the **first** open/sync of a new worktree to be slow (Gradle + indexes). Later opens via *Recent Projects* are usually much faster; `~/.gradle` is shared across projects.
4. Prefer **one** Android Studio window for the branch you are flashing to a device; leave agents editing in Cursor/Antigravity on their worktree paths.
5. Use the primary clone in Android Studio only for the default branch (e.g. `main`), or when no other worktree holds the branch you need.

**Do not:** switch branches inside one Android Studio window to reach a branch already checked out in another worktree.

## What not to do

- Do not tell Agent B to `git checkout` Agent A’s branch in the same folder.
- Do not stash Agent A’s WIP so Agent B can run (unless the owner explicitly requests a stash).
- Do not share uncommitted working trees across agents.
- Do not delete another agent’s worktree or branch without owner authorization and reachability checks.

## Anti-loss (keep work findable)

- Commit meaningful WIP on its branch before leaving a worktree idle (`WIP:` commits are fine).
- After the first meaningful commit, push the living branch: `git push -u origin HEAD`.
- Prefer commits in the branch worktree over long-lived stashes.
- Before `git worktree remove` or `git branch -d`: confirm commits are pushed or merged; do not delete unpushed unique work.
- Discover other work with `git worktree list`, `git branch -vv`, `git stash list`, and `python scripts/validate_handoffs.py` — not a separate inventory doc.

## Stash ownership

- Prefer **no stash** when using worktrees.
- If a stash is unavoidable, message must be: `wip <exact-branch-name>: <short reason>`.
- Record that exact message in the branch handoff Current status.
- Pop/apply only when checked out on the named branch **and** the current handoff lists that stash.
- Never pop/drop a stash named for another branch, or one not listed in the current handoff.
- Drop only after the work is committed on the named branch or the owner abandons it.
- Do not trust `stash@{N}` alone — indexes shift; ownership is **message branch name + handoff note**.

## Optional alternatives (usually secondary)

1. **Remote/cloud agents** with isolated sandboxes — fine when the product provides full clone isolation; still use one branch per task and keep handoffs updated.
2. **Separate clones** of the same remote — works but wastes disk and duplicates fetches; prefer worktrees for local parallelism.

For this repository’s branch naming, merge, and permission policy, see [REPOSITORY.md](REPOSITORY.md). For handoff lifecycle, see [MASTER_HANDOFF.md](MASTER_HANDOFF.md).
