# History: `main`

Append-only milestones for `main`. Current state and next actions belong in [main.md](main.md).

### 2026-07-17 — Published indicator update
- Commit/base: `2eaab30`
- Change: Adjusted Python bathyphase/acrophase indicator sizing and z-order.
- Validation/decision: Pushed to `origin/main`; designated the shared base for upcoming feature PRs.

### 2026-07-17 — Merged stacked feature work
- Commit/base: `4e8d4f2`, then `339c651`
- Change: Merged dynamic-sun checkpoint and public-priority follow-on, including permanent handoffs.
- Validation/decision: Owner requested commit/merge/push and post-merge archive cleanup for a clean development baseline.

### 2026-07-17 — Archived closed feature handoffs
- Commit/base: `90e2b49`
- Change: Moved closed branch docs to `archive/`, appended global HISTORY, deleted merged feature branches.
- Validation/decision: Repo left on clean `main` synced with `origin/main`.

### 2026-07-17 — Merged portable handoff kit
- Commit/base: tip `3c30ffe`; merge `bc0fad3`
- Change: Merged templates, agent entry points, repository policy, validator, and CI; archived the chore branch.
- Validation/decision: Owner requested commit/merge/push for a clean development baseline.

### 2026-07-18 — Merged bedtime countdown feature
- Commit/base: merge 52444d9; tip 94ed3ce
- Change: Merged 10pm floor and optional home/lock wallpaper bedtime countdown; archived feature handoff.
- Validation/decision: Owner requested commit and merge; push not requested.

### 2026-07-18 — Wind-down wedge darker gradient
- Commit/base: 64df715; merge dea5ea7
- Change: Wind-down SweepGradient from #5C5C5C to #111111 (was light/dark gray, then #444444).
- Validation/decision: Owner requested color tweaks, then commit and merge; push not requested.

### 2026-07-18 — Merged settings UI polish
- Commit/base: tip `412e961`; merge `c13f36a`
- Change: Switch toggles for settings; Canvas glyphs on Display Settings rows; archived feature handoff.
- Validation/decision: Owner requested commit and merge; push not requested.

### 2026-07-18 — Merged timezone world map
- Commit/base: tip `7908253`; merge `f0ee7c5`
- Change: Equirectangular timezone map with meridian + location dot; archived feature handoff.
- Validation/decision: Owner requested commit and merge; push not requested.

### 2026-07-18 — Merged empirical log Jul 16 cutoff
- Commit/base: tip `af95262`; merge `b2de326`
- Change: Pruned empirical logs before 2026-07-16; 10pm missed alert is today-only; Drive web app URL autofills with external label.
- Validation/decision: Owner requested commit and merge; push not requested.

### 2026-07-18 — Merged multi-agent worktree docs
- Commit/base: tip/merge `520511e` (fast-forward)
- Change: Multi-agent worktree protocol, lean anti-loss/stash ownership, validator parallel-state ledger; archived chore handoff.
- Validation/decision: Owner directed lean anti-loss plan implement/merge; stashes renamed to compliant messages.

### 2026-07-18 — Merged energy Drive backup (PR #1)
- Commit/base: tip `ff1c762`; merge `c4de4b2`; PR https://github.com/briansgithub/24-hr_clock_widget/pull/1
- Change: Real-time Drive upload after user energy entry; prompt datetime label; archived feature handoff.
- Validation/decision: Owner smoke PASSED; post-merge archive and branch deletion authorized.

### 2026-07-18 — Synced tip after energy archive
- Commit/base: `7239aa0`
- Change: Archived energy handoffs; deleted local/remote feature branch; refreshed active index.
- Validation/decision: `python scripts/validate_handoffs.py` passed.

### 2026-07-18 — Merged display Home/Lock UX (PR #2)
- Commit/base: tip `354a8db`; merge `cd4f97d`; PR https://github.com/briansgithub/24-hr_clock_widget/pull/2
- Change: Display Preview removed; tab-scoped Reset; Home/Lock icons; archived feature handoff.
- Validation/decision: Owner visual check PASSED; post-merge archive and branch/worktree deletion authorized.

### 2026-07-18 — Merged exercise metric help (PR #3)
- Commit/base: tip `d1fac1a`; merge `ed22464`; PR https://github.com/briansgithub/24-hr_clock_widget/pull/3
- Change: Three-level HRSS/TRIMP/HRV help via chart legend; archived feature handoff; exercise stash dropped.
- Validation/decision: Owner authorized merge, post-merge cleanup, and stash drop.

### 2026-07-18 — Android Studio worktree workflow documented
- Change: Added Android Studio section to `MULTI_AGENT.md` (open worktree as project; copy `local.properties`; no branch-switch into worktree-held branches); `REPOSITORY.md` pointer. Dropped superseded inventory stash.
- Validation/decision: Owner authorized restore/commit of docs stash and drop of pre-energy inventory stash.
