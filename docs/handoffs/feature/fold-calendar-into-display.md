# Branch: `feature/fold-calendar-into-display`

## Identity

- Tip: `a45246e` — Fold Calendar settings into Display with accordion picker.
- Parent/base: `main` at `02fa6f4`
- Upstream: none
- Role: feature
- History: [fold-calendar-into-display.history.md](fold-calendar-into-display.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Fold the Calendar bottom-nav screen into Display with a condensed accordion calendar picker.
- Included: Move `showCalendar` into Lock/Home Display tabs; accordion calendar selection; sync/auth into Display CALENDAR section; remove Calendar nav item; `GlyphCalendarEvents`.
- Excluded: Settings schema changes; wallpaper/renderer behavior changes.
- Acceptance criteria: No Calendar tab; Display Lock/Home each have Show Calendar Events; when on, expandable Calendars list; sync/auth works from Display; selection shared across surfaces.

## Changes since branch creation

- Uncommitted: Removed Calendar bottom-nav screen; single CALENDAR section at bottom of Display (events toggle, accordion picker, sync/auth); larger section headers via DisplaySectionHeader.

## Current status

- Working tree: clean after `a45246e`
- Base relationship: branched from `main` `02fa6f4`
- Validation: handoff validator pending; Android compile not run in this session
- Risks/blockers: none known
- Stashes: none

## Next actions

1. Owner: commit when ready; manual check Lock/Home toggles, accordion, sync, bottom nav.
2. Open PR to `main` after owner-approved checks.

## Merge and cleanup

- PR base: `main`
- Readiness: ready after checks
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
