# Archived: `feature/fold-calendar-into-display`

## Identity

- Final tip: `dc74564` — docs: record fold-calendar-into-display tip a45246e
- Feature tip: `a45246e` — Fold Calendar settings into Display with accordion picker
- Merge / PR: `43f0872` — Merge fold-calendar into Display settings (local merge)
- Parent/base: `main` at `02fa6f4`
- History: [fold-calendar-into-display.history.md](fold-calendar-into-display.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-22`

## Goal and scope

- Fold the Calendar bottom-nav screen into Display with a condensed accordion calendar picker.
- Lock/Home Display tabs each have Show Calendar Events; shared calendar selection; sync/auth in Display CALENDAR section.

## What landed

- Removed Calendar bottom-nav screen; single CALENDAR section at bottom of Display (events toggle, accordion picker, sync/auth).
- `GlyphCalendarEvents`; larger section headers via `DisplaySectionHeader`.

## Validation recorded

- Owner UI polish iterations (accordion default collapsed, consolidated CALENDAR block).
- Merged into `main` at `43f0872`.

## Cleanup

- Local branch deleted: yes (`git branch -D`; tip `7192f89` was checkout checkpoint only)
- Remote branch deleted: n/a (never pushed)
