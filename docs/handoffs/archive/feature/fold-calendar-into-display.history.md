# History: `feature/fold-calendar-into-display`

Append-only milestones. Archived with [fold-calendar-into-display.md](fold-calendar-into-display.md).

### 2026-07-18 — Branch created
- Commit/base: `main` at `02fa6f4`
- Change: Created `feature/fold-calendar-into-display` to fold Calendar into Display with accordion picker (option D).
- Validation/decision: Scope locked in plan; sync/auth moves into Display CALENDAR block.

### 2026-07-18 — Calendar folded into Display
- Commit/base: uncommitted
- Change: Removed Calendar bottom-nav screen; Display Lock/Home now own Show Calendar Events with accordion calendar picker and sync/auth; added GlyphCalendarEvents.
- Validation/decision: Implementation complete; commit/PR pending owner.

### 2026-07-22 — Display calendar UI polish
- Commit/base: uncommitted
- Change: Calendars accordion collapsed by default, moved to bottom of Display; section headers use larger titleMedium style including CALENDARS.
- Validation/decision: UI-only tweak per owner request.

### 2026-07-22 — Single CALENDAR section at bottom
- Commit/base: uncommitted
- Change: Consolidated all calendar settings (events toggle, accordion picker, sync/auth) under one CALENDAR heading at the bottom of Display.
- Validation/decision: UI-only tweak per owner request.

### 2026-07-22 — Merged into main; branch deleted
- Merge: `43f0872`
- Change: Feature commits reachable on `main`; handoff archived; local git branch deleted per owner request.
- Validation/decision: `a45246e` ancestor of `main`; branch tip `7192f89` was checkout checkpoint only.
