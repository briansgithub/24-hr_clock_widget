# Branch: `<branch-name>`

## Identity

- Tip: `<sha>` — `<subject>`
- Parent/base: `<branch>` at `<sha>`
- Upstream: `<remote-branch or none>`
- Role: `<feature/fix/chore/integration>`
- History: [<filename>.history.md](<filename>.history.md)
- Last verified: `YYYY-MM-DD`

## Goal and scope

- Goal: `<why this branch exists>`
- Included: `<bounded scope>`
- Excluded: `<explicit non-goals>`
- Acceptance criteria: `<observable completion conditions>`

## Changes since branch creation

- `<sha or uncommitted>`: `<concise behavior change>`

## Current status

- Working tree: `<clean/dirty; confirmed intent>`
- Base relationship: `<ahead/behind/diverged>`
- Validation: `<latest commands/results or not run>`
- Risks/blockers: `<known issues or none known>`
- Stashes: `<state and disposition>`

## Next actions

1. `<single immediate next action>`
2. `<remaining concrete action>`

## Merge and cleanup

- PR base: `<target>`
- Readiness: `<blocked/in progress/ready after checks/ready>`
- Required predecessor: `<branch/PR or none>`
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.

