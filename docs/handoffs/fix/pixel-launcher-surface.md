# Branch: `fix/pixel-launcher-surface`

## Identity

- Parent/base: `main` at `99756fe`
- Upstream: none
- Role: fix
- Worktree: `H:/Desktop/widgets/24-hr_clock_widget`
- Owned paths: Android wallpaper, bedtime notification, location permission, handoff index
- History: [pixel-launcher-surface.history.md](pixel-launcher-surface.history.md)
- Last verified: `2026-11-05`

## Goal and scope

- Goal: Stop the live wallpaper from fighting Pixel Launcher on the Pixel 7a and Pixel 9 Pro.
- Included: draw only while visible and zoom-idle, stable wallpaper colors, shared calendar feed, stored location instead of background GPS, inexact bedtime alarm, chronometer notification without a foreground service, density-based dial insets.
- Excluded: target SDK bump, energy-model changes, push to origin.
- Acceptance criteria: `:app:testDebugUnitTest` and `:app:assembleDebug` pass, then a data-preserving install on each connected Pixel.

## Changes since branch creation

- Wallpaper posts frames only when visible and zoom is idle, and reports stable black `WallpaperColors`.
- Countdown seconds follow the 10-second full frame. The partial `lockCanvas` path and `BedtimeCountdownService` are gone.
- One calendar observer for the process. Location is saved while the activity is resumed. Background location, exact-alarm, and foreground-service permissions are removed.

## Current status

- Working tree: implementation ready to commit.
- Validation: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` passed (`pixel-launcher-android`).
- Risks: wallpaper zoom behavior still needs a look on both Pixels after install. Re-applying the live wallpaper may be required before the new engine is the one on screen.
- Stashes: none.

## Next actions

1. Merge into local `main` and install the debug build without clearing app data.
2. On each phone, confirm the home screen does not flash or smear while opening an app and returning.

## Merge and cleanup

- PR base: `main`
- Readiness: ready for local merge
- Remote branch deleted: not applicable
