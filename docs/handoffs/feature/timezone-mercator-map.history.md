# History: `feature/timezone-mercator-map`

Append-only milestones for this branch. Current state and next actions belong in [`timezone-mercator-map.md`](timezone-mercator-map.md).

### 2026-07-18 — Branch created
- Commit/base: `main` at `c13f36a`
- Change: Local branch created for timezone / Mercator map work after settings UI polish merge.
- Validation/decision: Active handoff restored so living-branch inventory validates.

### 2026-07-18 — Mercator map display implemented
- Commit/base: uncommitted on `main` @ `2a3614f`
- Change: Added `showTimezoneMap` (home off / lock on), lower-half outline map + amber meridian at correlated timezone longitude, Display Settings toggle/glyph, and `TimeZoneUtils` helpers.
- Validation/decision: Kotlin compile + `TimeZoneUtilsTest` passed; owner visual check pending before commit.

### 2026-07-18 — Map raised under clock + location dot
- Commit/base: uncommitted on `2a3614f`
- Change: Top-aligned map just below sun/moon orbit clearance; tiny white Mercator-projected device location dot from wallpaper/preview lat/lon.
- Validation/decision: Recompile pending owner visual check.

### 2026-07-18 — Switch to equirectangular map + accurate dot
- Commit/base: uncommitted on `2a3614f`
- Change: Replaced decorative non-georeferenced PNG with equirectangular blank world map; `latLonToMapPoint` now uses linear lon/lat; Display label “Timezone World Map”.
- Validation/decision: Compile passed; offline spot-check places NYC/London/Tokyo/LA/Sydney on land and oceans empty.

### 2026-07-18 — Larger location dot + real mini-map glyph
- Commit/base: uncommitted on `2a3614f`
- Change: Bumped white location-dot radius; Display glyph now renders `world_map_equirectangular` with meridian overlay. Home/lock defaults remain off/on.
- Validation/decision: Compile after glyph change.

### 2026-07-18 — Numbers glyph as white dial ring
- Commit/base: uncommitted on `2a3614f`
- Change: `GlyphNumbers` draws the same even-hour 12-hour numerals as `ClockRenderer` in a white ring (no dial face).
- Validation/decision: Owner visual check pending.

### 2026-07-18 — Subtract awake toggle + glyph
- Commit/base: uncommitted on `2a3614f`
- Change: Renamed Display “Time in Bed…” to “Subtract awake”; UI switch inverted vs `showTotalBedtime` (on = asleep-only); glyph = purple sleep wedge with red first 15%.
- Validation/decision: Owner visual check pending.

### 2026-07-18 — Bathy/acro phase glyphs
- Commit/base: uncommitted on `2a3614f`
- Change: Glyphs use energy-curve min (`#00D2FF`) / max (`#FF4B2B`); triangle base = 0.8×height like ClockRenderer; drawn smaller (~48% of glyph box).
- Validation/decision: Owner visual check pending.

### 2026-07-18 — Location dot mid-size
- Commit/base: uncommitted on `2a3614f`
- Change: White map dot radius set between original (`1.5`/`0.25%`) and prior bump (`3.5`/`0.6%`) → `2.5`/`0.425%`.
- Validation/decision: Owner visual check pending.

### 2026-07-18 — Sun/Moon glyph crescent fix
- Commit/base: uncommitted on `2a3614f`
- Change: Replaced offset-circle moon hack with ClockRenderer phase draw (shadow disk + lit semicircle + terminator oval).
- Validation/decision: Owner visual check pending.

### 2026-07-18 — Display label Title Case
- Commit/base: uncommitted on `2a3614f`
- Change: Normalized Display toggle capitalization to Title Case (`Subtract Awake`, `Bathyphase Indicator`, `Acrophase Indicator`).
- Validation/decision: Done.
