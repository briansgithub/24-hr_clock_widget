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
