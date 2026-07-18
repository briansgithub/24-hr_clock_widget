# Branch Handoff History

Write-only repository ledger of closed branches. Append exactly one close entry per branch at the bottom. Never rewrite older entries.

When this file approaches ~30,000 characters, start `HISTORY/YYYY.md` for the new year and leave a pointer here — do not rewrite prior years.

Detailed living milestones belong in each branch’s adjacent `.history.md`; those histories are archived with their handoffs. Archived full snapshots live under `archive/` (mirrored branch paths). Active living branches are indexed in [README.md](README.md). Protocol: [MASTER_HANDOFF.md](MASTER_HANDOFF.md).

## Entry format

```markdown
### YYYY-MM-DD — `feature/example` → merged into `main`
- Final tip: `<sha>`
- Merge / PR: `<sha or URL>`
- Summary: <one or two sentences>
- Archive: `archive/feature/example.md`
```

Disposition phrases: `merged into main`, `superseded` (commits landed via descendant), `closed-without-merge`.

## Entries

<!-- Append below this line. Do not edit entries above a new append except to fix factual errors in HISTORY only when correcting a mistaken prior append; prefer a correcting follow-up entry if unsure. -->

### 2026-07-17 — `feature/dynamic-sun-color` → merged into `main`
- Final tip: `34b2b64`
- Merge / PR: `4e8d4f2`
- Summary: Checkpoint merge for dynamic sun, bedtime refinements, grogginess sync, and empirical logging foundations.
- Archive: `archive/feature/dynamic-sun-color.md`

### 2026-07-17 — `feature/empirical-energy-logging` → superseded
- Final tip: `2983057`
- Merge / PR: reachable via `4e8d4f2` / `339c651` (no standalone PR)
- Summary: Intermediate empirical logging branch closed after descendant merges landed its commits.
- Archive: `archive/feature/empirical-energy-logging.md`

### 2026-07-17 — `feature/empirical-public-priority` → merged into `main`
- Final tip: `1bca4ad`
- Merge / PR: `339c651`
- Summary: Public CSV priority, Sync conflict UI, night-sun visibility, and permanent handoff documentation.
- Archive: `archive/feature/empirical-public-priority.md`

### 2026-07-17 — `chore/portable-handoff-kit` → merged into `main`
- Final tip: `3c30ffe`
- Merge / PR: `bc0fad3`
- Summary: Portable templates, agent entry points, repository policy, handoff validator, and CI.
- Archive: `archive/chore/portable-handoff-kit.md`

### 2026-07-18 — eature/bedtime-countdown-10pm-floor → merged into main
- Final tip: 94ed3ce
- Merge / PR: 52444d9
- Summary: 10pm bedtime floor plus optional home/lock wallpaper countdown with Display Sleep toggle.
- Archive: rchive/feature/bedtime-countdown-10pm-floor.md

### 2026-07-18 — eature/android-settings-ui-polish → merged into main
- Final tip: 412e961
- Merge / PR: c13f36a
- Summary: Switch-based settings toggles and Canvas Display element glyphs.
- Archive: rchive/feature/android-settings-ui-polish.md

### 2026-07-18 — eature/timezone-mercator-map → merged into main
- Final tip: 7908253
- Merge / PR: 0ee7c5
- Summary: Equirectangular timezone world map with correlated meridian and phone location dot (lock on / home off by default).
- Archive: rchive/feature/timezone-mercator-map.md

### 2026-07-18 — `feature/empirical-log-jul16-cutoff` → merged into `main`
- Final tip: `af95262`
- Merge / PR: `b2de326`
- Summary: Jul 16 2026 empirical log cutoff, today-only 10pm missed alert, and Drive web app URL autofill.
- Archive: `archive/feature/empirical-log-jul16-cutoff.md`

### 2026-07-18 — `chore/multi-agent-worktree-docs` → merged into `main`
- Final tip: `520511e`
- Merge / PR: `520511e` (fast-forward)
- Summary: Multi-agent worktree protocol plus lean anti-loss/stash ownership rules; validator parallel-state ledger.
- Archive: `archive/chore/multi-agent-worktree-docs.md`

### 2026-07-18 — `feature/energy-entry-backup-drive` → merged into `main`
- Final tip: `ff1c762`
- Merge / PR: `c4de4b2` / https://github.com/briansgithub/24-hr_clock_widget/pull/1
- Summary: Real-time Google Drive sync after user energy entry; prompt datetime label; MISSED seeds skip Drive.
- Archive: `archive/feature/energy-entry-backup-drive.md`
