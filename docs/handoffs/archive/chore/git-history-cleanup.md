# Archived branch: `chore/git-history-cleanup`

## Goal and scope

Audit local branches, worktrees, stashes, and nested repository state; remove only fully merged local branch labels; archive closed handoffs; preserve ambiguous or unique work.

## Final status

- Three fully merged local branch labels were removed after reachability verification.
- `feature/energy-refit-jul29` remains open because its separate worktree is dirty and contains untracked files.
- Two July 29 stashes remain because their content is not redundant with current `main`.
- The outer wrapper repository remains untouched pending owner disposition.

## Merge and cleanup

- Base: `main` at `aec4184`.
- Disposition: validated and prepared for an explicit `--no-ff` merge into `main`; local branch label can be deleted after reachability verification.
