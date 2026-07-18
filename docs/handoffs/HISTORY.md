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
