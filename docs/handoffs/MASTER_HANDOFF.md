# Master Branch Handoff Protocol

Permanent handoffs live in `docs/handoffs/` and must be committed with the repository. Read this file first, then `README.md`, then the file matching the current branch.

## Non-negotiable rules

1. **Always current:** Any agent that changes code, configuration, git state, branch state, stash state, tests, scope, or next steps must update the affected handoff and `README.md` before finishing.
2. **Replacement, not accumulation:** Clear stale subsection content and rewrite it with the latest verified state. Do not append session logs, duplicate old summaries, or preserve superseded checklists.
3. **Strict size limit:** Every handoff Markdown file, including this one and `README.md`, must remain **strictly below 42,000 characters**. Target under 12,000 characters; branch files should normally stay under 6,000.
4. **Git is evidence:** Verify handoffs against git before relying on them. Label unresolved intent rather than guessing.
5. **Persistent and tracked:** Never move these files to a temporary/ignored directory. Include relevant handoff updates in the same commit or PR as the change they describe.
6. **Ready at interruption:** Write handoffs so another agent can take over immediately, including while work is uncommitted.

Documentation-only edits do not require recursive “handoff of the handoff” updates unless they alter branch status, decisions, or next actions.

## File layout

Mirror local branch names:

- `main` → `docs/handoffs/main.md`
- `feature/example` → `docs/handoffs/feature/example.md`

`README.md` is the current branch index and merge-order authority. Keep one file for every active local branch tip. Remove a branch file after the branch is safely deleted; retain only a compact disposition note in `README.md` when useful.

## Required startup procedure

Before changing the repository:

1. Read this file, `README.md`, and the current branch handoff.
2. Verify:
   - `git status`;
   - local/remote branch tips and upstream tracking;
   - merge base, commits, and diff against `origin/main`;
   - related stashes;
   - existing tests/build status when relevant.
3. Compare evidence with the handoff.
4. Correct stale factual content immediately.
5. Ask the owner only for intent git cannot prove.

Do not assume visible uncommitted changes belong to the checked-out branch; confirm from the handoff or owner.

## Evidence language

- **Git-verified:** commits, ancestry, files, tracking, clean/dirty state.
- **Owner-confirmed:** scope, priorities, merge order, known gaps, disposition.
- **Unknown:** unresolved historical context or intent.
- **Recommendation:** unapproved agent advice.

Use exact commit IDs and an ISO `Last verified` date. Avoid “recent,” “mostly done,” and similar vague status.

## Required branch-file structure

Keep these compact sections and replace their contents whenever state changes:

### Identity

- Branch, tip commit/subject, upstream, merge base or parent, role, last verified date.

### Goal and scope

- Why the branch exists.
- Included and excluded scope.
- Acceptance criteria.

### Changes since branch creation

- Concise ordered commit/behavior summary.
- Important compatibility, migration, configuration, or secret implications.
- Summarize; do not paste full diffs or permanent session history.

### Current status

- Clean/dirty working tree and confirmed intent of uncommitted files.
- Ahead/behind/diverged state.
- Latest test/build/manual-check results.
- Known bugs, risks, blockers, and stashes.

### Next actions

- Short ordered checklist with concrete completion conditions.
- Put the single immediate next action first.
- Remove completed items instead of keeping a growing checked-off archive.

### Merge and cleanup

- Intended PR base and predecessor PRs.
- Current readiness: blocked / in progress / ready after checks / ready.
- Required checks.
- Branches made obsolete by merge.
- Local/remote deletion criteria.

## End-of-change update procedure

After every agent change and before the final response:

1. Re-run enough git inspection to know the resulting state.
2. Rewrite the affected branch handoff’s tip, current status, tests, risks, and next actions.
3. Rewrite `README.md` if tips, roles, dependency order, or branch inventory changed.
4. Remove stale, completed, duplicated, and superseded text.
5. Check every edited handoff is below 42,000 characters.
6. Ensure links and branch-mirrored paths are correct.
7. Include the handoff updates with the implementation change when committing.

If interrupted before implementation completes, record exactly what changed, what remains uncommitted, what was tested, and the first safe next action.

## PR checks and merge readiness

Before **every** PR, the agent must propose a focused automated/manual check list and ask the owner to approve, add, remove, or defer checks. Record only the latest approved list and results in the branch handoff.

A branch is merge-ready only when:

- scope and acceptance criteria are complete;
- unrelated WIP is absent and the working tree is clean;
- owner-approved tests/builds/manual checks pass or explicit deferrals are recorded;
- no known high-severity regression remains;
- the branch is based on the correct current target or predecessor;
- secrets, generated data, and IDE-local state are excluded;
- the handoff matches the final tip and states residual risks;
- the PR summary explains purpose, behavior, validation, and dependencies.

“No known gaps” is not proof that validation passed.

## Stash and branch cleanup

- A stash may be deleted under the owner’s standing instruction only after comparing it with reachable commits and verifying all meaningful contents are fully implemented. Record the conclusion before deletion.
- Never delete a unique stash or branch containing unique wanted commits.
- After merge: update `main`, verify commits are reachable from `origin/main`, delete merged local/remote branches when permitted, remove their branch handoff, and refresh `README.md`.
- Delete fully superseded branches promptly. Archive only genuinely useful unmerged history, with a concise reason.

## New-feature practice

1. Update from `origin/main`.
2. Create one narrow `feature/`, `fix/`, or `chore/` branch.
3. Immediately create its mirrored handoff with goal, scope, acceptance criteria, and parent.
4. Prefer small purpose-focused commits; exclude secrets, generated output, IDE state, and unrelated formatting.
5. Avoid stacked branches unless intentional; document dependency and PR order.
6. Commit or clearly label a stash before switching branches.
7. Keep handoffs current after each agent change, not only at session end or PR time.
8. Ask for PR checks, validate, merge promptly, then clean up.

## Prompt for future implementation agents

> Read `docs/handoffs/MASTER_HANDOFF.md`, `docs/handoffs/README.md`, and the handoff matching the current branch. Verify them against git status, branch tracking, merge-base history, commits, diffs, tests, and relevant stashes. Report contradictions and ask only for intent git cannot establish. Implement the documented immediate next action. Before every PR, propose automated/manual checks and ask me to approve or revise them. Before finishing any repository change, replace stale content in the affected handoff and index with the latest tip, current working state, validation, risks, immediate next action, merge readiness, and cleanup implications. Keep every handoff strictly below 42,000 characters. Do not commit, push, open/merge PRs, or delete branches unless explicitly requested. You may delete a superseded stash only after verifying all meaningful contents are implemented in reachable commits.

## Prompt for handoff audits

> Audit `docs/handoffs/` using `MASTER_HANDOFF.md`. Verify every active local branch tip against git. Replace stale subsection content with current facts, remove accumulated history and completed checklist items, preserve owner-confirmed current decisions, check all files are strictly below 42,000 characters, and ask focused questions only for unresolved intent. Make no code, history, remote, stash, or branch mutations.

