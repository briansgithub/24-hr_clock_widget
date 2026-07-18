# History: `feature/android-settings-ui-polish`

Append-only milestones for this branch. Current state and next actions belong in [android-settings-ui-polish.md](android-settings-ui-polish.md).

### 2026-07-18 — Branch created / WIP stashed
- Commit/base: `main` at `b0187f4`
- Change: Settings UI polish WIP started; stashed as `stash@{0}` while switching to bedtime-countdown branch for timer position/battery follow-up.
- Validation/decision: Resume via stash pop on this branch.

### 2026-07-18 — Rebased onto main after bedtime + wind-down
- Commit/base: reset branch tip to `main` @ `71aee00`; restored stash WIP
- Change: Resolved MainActivity conflict by keeping polished Settings sections and re-adding Wind-down Wedge + Bedtime Countdown toggles. Removed stale active bedtime handoffs (already archived).
- Validation/decision: Continue on current main; stash left as backup until commit.

### 2026-07-18 — Fix SettingToggle trailing-lambda signature
- Commit/base: uncommitted on `71aee00`
- Change: Moved `onCheckedChange` / button `onClick` to last parameter so trailing-lambda call sites compile (Studio: MainActivity:648).
- Validation/decision: Owner to rebuild in Android Studio.

### 2026-07-18 — Narrow scope to Switch + Display icons
- Commit/base: uncommitted on `71aee00` (restored MainActivity/theme/Empirical from `main`)
- Change: Owner rejected broader aesthetic pass. Kept only Checkbox→Switch in `SettingToggle` and added leading Material icons on Display Settings toggles. Deleted `SettingsComponents.kt` and theme redesign.
- Validation/decision: Scope narrowed per owner; rebuild pending.

### 2026-07-18 — Display glyphs replace Material icons
- Commit/base: uncommitted on `71aee00`
- Change: Replaced Material icons with tiny Canvas previews in `DisplayElementGlyphs.kt` (clock-face colors/shapes). `SettingToggle` takes optional leading composable.
- Validation/decision: Owner will flag glyphs that need adjustment.

### 2026-07-18 — Glyphs: element-only (except Small Top-Right)
- Commit/base: uncommitted on `71aee00`
- Change: Removed night/day dial backgrounds from all Display glyphs except `GlyphSmallTopRight`, which still shows a mini top-right clock.
- Validation/decision: Awaiting owner visual feedback.

### 2026-07-18 — Glyph refinements (UTC, wake tick, wedges, curves)
- Commit/base: uncommitted on `71aee00`
- Change: `±UTC` text; wake tick at 9:00 geometry; grogginess@6am / wind-down@8pm; bed+stopwatch; circular harmonic energy; gaussian normalize.
- Validation/decision: Awaiting owner visual feedback.
