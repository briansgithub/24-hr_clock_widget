# Master Branch Handoff Protocol

Permanent handoffs live in `docs/handoffs/` and must be committed with the repository. Read this file first, then `README.md`, then the file matching the current branch.

## Non-negotiable rules

1. **Always current:** Any agent that changes code, configuration, git state, branch state, stash state, tests, scope, or next steps must update the affected handoff and `README.md` before finishing.
2. **Replacement, not accumulation (active only):** Clear stale subsection content and rewrite it with the latest verified state. Do not append session logs, duplicate old summaries, or preserve superseded checklists in *active* branch handoffs.
3. **Strict size limit:** Every handoff Markdown file, including this one and `README.md`, must remain **strictly below 42,000 characters**. Target under 12,000 characters; active branch files should normally stay under 6,000; archived closed forms under 3,000.
4. **Git is evidence:** Verify handoffs against git before relying on them. Label unresolved intent rather than guessing.
5. **Persistent and tracked:** Never move these files to a temporary/ignored directory. Include relevant handoff updates in the same commit or PR as the change they describe.
6. **Ready at interruption:** Write handoffs so another agent can take over immediately, including while work is uncommitted.
7. **Never delete handoff docs:** When a branch closes, **archive** its handoff and per-branch history, then append the global `HISTORY.md`. Do not delete either branch document.

Documentation-only edits do not require recursive “handoff of the handoff” updates unless they alter branch status, decisions, or next actions.

## File layout

### Active (living branches)

Mirror local branch names:

- `main` → `docs/handoffs/main.md`
- `main` history → `docs/handoffs/main.history.md`
- `feature/example` → `docs/handoffs/feature/example.md`
- `feature/example` history → `docs/handoffs/feature/example.history.md`

`README.md` is the current branch index and merge-order authority. Keep one active handoff and one append-only history for every living local branch tip.

### Permanent archive and history

| Artifact | Role | Mutation rule |
|---|---|---|
| Active `docs/handoffs/<branch>.md` | Live agent operating doc | Replace/update while branch is alive |
| Active `docs/handoffs/<branch>.history.md` | Dated milestones for that branch | Append only; never rewrite older entries |
| `docs/handoffs/archive/<same-path>.md` | Frozen snapshot at close | Write once at archive time; later edits only to fix factual errors |
| `docs/handoffs/archive/<same-path>.history.md` | Frozen branch milestone log | Move unchanged at close; later edits only to fix factual errors |
| `docs/handoffs/HISTORY.md` | Repository-wide closed-branch ledger | Append one close entry only; never rewrite older entries |
| `docs/handoffs/README.md` | Active branch index | Lists living branches only; optional one-line “recently archived” pointer |

Example after merge:

- `docs/handoffs/feature/foo.md` → `docs/handoffs/archive/feature/foo.md`
- `docs/handoffs/feature/foo.history.md` → `docs/handoffs/archive/feature/foo.history.md`

The active handoff answers “what is true and what is next?” The per-branch history answers “what meaningful milestones occurred while this branch lived?” The global `HISTORY.md` answers “which branches closed, when, and where are their archives?”

## Required startup procedure

Before changing the repository:

1. Read this file, `README.md`, the current branch handoff, and its adjacent `.history.md`.
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

## Per-branch history protocol

Create `<branch>.history.md` at the same time as every branch handoff. It is append-only and must stay concise.

Append one dated entry for a meaningful branch milestone:

- branch creation and parent tip;
- meaningful commit or grouped implementation milestone;
- owner-approved scope or merge-order change;
- approved PR check list and test results;
- rebase/merge-base change;
- PR opened, updated, merged, superseded, or closed;
- stash creation/disposition when it affects recoverability.

Do not append entries for routine file reads, status checks, wording-only handoff refreshes, or repeated facts. Link commit SHAs; do not paste diffs. Suggested format:

```markdown
### YYYY-MM-DD — Short milestone
- Commit/base: `<sha>` (when applicable)
- Change: <one or two sentences>
- Validation/decision: <concise result>
```

Never rewrite or reorder older entries. Correct an error with a new correction entry. If a per-branch history approaches 30,000 characters, freeze it unchanged as `<branch>.history/0001.md`, create a fresh `<branch>.history.md` that links the frozen segment, and continue appending there. Every file must remain strictly below 42,000 characters.

## Required branch-file structure (active)

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
3. Append a concise entry to the branch’s `.history.md` when the change is a meaningful milestone under the per-branch history protocol.
4. Rewrite `README.md` if tips, roles, dependency order, or branch inventory changed.
5. Remove stale, completed, duplicated, and superseded text from *active handoffs only*.
6. Check every edited handoff/history file is below 42,000 characters.
7. Ensure links and branch-mirrored paths are correct.
8. Include the handoff/history updates with the implementation change when committing.

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

## After merge and pull

When a feature PR has been merged into `main` (or a superseded intermediate’s commits are fully on `main`), agents must complete this checklist. Do not skip archival in favor of deleting handoff files.

### Git cleanup

1. `git checkout main`
2. `git pull origin main`
3. Confirm the feature tip (or merge commit) is reachable from `origin/main`
4. Delete the remote feature branch (`git push origin --delete <branch>` or GitHub delete-branch)
5. Delete the local feature branch (`git branch -d <branch>`; use `-D` only if reachability was verified and `-d` refuses for a known-safe reason)
6. Do not keep merged feature branches “just in case”; history on `main` is the source of truth

Superseded intermediates follow the same delete path once their commits are on `main`.

### Handoff archival (mandatory)

1. **Do not delete** the handoff or its per-branch history.
2. Move both to mirrored archive paths:
   - `docs/handoffs/feature/foo.md` → `docs/handoffs/archive/feature/foo.md`
   - `docs/handoffs/feature/foo.history.md` → `docs/handoffs/archive/feature/foo.history.md`
3. Rewrite the archived file to a **frozen closed form** (target under 3,000 characters):
   - Identity (final tip, merge commit / PR URL if known)
   - Goal and scope (final)
   - What landed (short)
   - Validation recorded
   - Disposition: `merged` / `superseded` / `closed-without-merge`
   - Cleanup completed: remote/local branch deleted yes/no
4. Append the final merge/closure milestone to the branch history before freezing it.
5. **Append** one close entry to global `docs/handoffs/HISTORY.md` (never rewrite older entries):

```markdown
### YYYY-MM-DD — `feature/foo` → merged into `main`
- Final tip: `<sha>`
- Merge / PR: `<sha or URL>`
- Summary: <one or two sentences>
- Archive: `archive/feature/foo.md`
```

6. Remove the branch from the active table in `docs/handoffs/README.md`.
7. Update `docs/handoffs/main.md` and append a corresponding milestone to `main.history.md` if `main` moved.
8. Commit these doc updates (same cleanup commit or immediately after).

`HISTORY.md` is append-only. If it approaches ~30,000 characters, start `HISTORY/YYYY.md` by year and leave a pointer at the top of `HISTORY.md` — do not rewrite old years. Archived files may be edited later only to correct factual errors, not to resume live checklists.

## Stash cleanup

- A stash may be deleted under the owner’s standing instruction only after comparing it with reachable commits and verifying all meaningful contents are fully implemented. Record the conclusion before deletion.
- Never delete a unique stash or a branch containing unique wanted commits.

## New-feature practice

1. Update from `origin/main`.
2. Create one narrow `feature/`, `fix/`, or `chore/` branch.
3. Immediately create its mirrored handoff and adjacent `.history.md`; record branch creation and parent tip in the history.
4. Prefer small purpose-focused commits; exclude secrets, generated output, IDE state, and unrelated formatting.
5. Avoid stacked branches unless intentional; document dependency and PR order.
6. Commit or clearly label a stash before switching branches.
7. Keep handoffs current after each agent change, not only at session end or PR time.
8. Ask for PR checks, validate, merge promptly, then run **After merge and pull**.

## Prompt for future implementation agents

> Read `docs/handoffs/MASTER_HANDOFF.md`, `docs/handoffs/README.md`, the handoff matching the current branch, and its adjacent `.history.md`. Verify them against git status, branch tracking, merge-base history, commits, diffs, tests, and relevant stashes. Report contradictions and ask only for intent git cannot establish. Implement the documented immediate next action. Before every PR, propose automated/manual checks and ask me to approve or revise them. Before finishing any repository change, replace stale content in the affected *active* handoff and index with the latest state, and append a concise per-branch history entry only for a meaningful milestone. After a feature is merged and pulled, append its closure milestone, archive both its handoff and per-branch history under `docs/handoffs/archive/`, append one close entry to global `HISTORY.md`, refresh the active index, and delete the remote/local git branch — never delete branch documentation. Keep every handoff/history file strictly below 42,000 characters. Do not commit, push, open/merge PRs, or delete branches unless explicitly requested. You may delete a superseded stash only after verifying all meaningful contents are implemented in reachable commits.

## Prompt for handoff audits

> Audit `docs/handoffs/` using `MASTER_HANDOFF.md`. Verify every active local branch tip against git and confirm it has both an active handoff and adjacent `.history.md`. Replace stale subsection content in active handoffs with current facts, remove completed checklist items from active handoffs only, preserve owner-confirmed decisions, leave all history files append-only and archived files frozen except for factual corrections, check every file is strictly below 42,000 characters, and ask focused questions only for unresolved intent. Make no code, git-history, remote, stash, or branch mutations unless I explicitly request cleanup.
