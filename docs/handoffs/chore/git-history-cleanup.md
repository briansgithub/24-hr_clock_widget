# Branch: `chore/git-history-cleanup`

## Goal and scope

Audit local branches, worktrees, stashes, and nested repository state; remove only fully merged local branch labels; archive closed handoffs; preserve ambiguous or unique work.

## Current status

- Three fully merged local branch labels were removed after reachability verification.
- `feature/energy-refit-jul29` remains open because its separate worktree is dirty and contains untracked files.
- Two July 29 stashes remain because their content is not redundant with current `main`.
- The outer wrapper repository remains untouched pending owner disposition.

## Next actions

1. Validate this handoff cleanup.
2. Commit and merge the cleanup into `main`.
3. Review the dirty worktree and stashes separately before deletion or promotion.

## Merge and cleanup

- Base: `main` at `aec4184`.
- Merge strategy: explicit `--no-ff` merge after validation.
