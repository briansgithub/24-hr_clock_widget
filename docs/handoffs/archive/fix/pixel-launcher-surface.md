# Archived branch: `fix/pixel-launcher-surface`

## Identity

- Final tip: `7173dca` — Record the Pixel launcher branch close in its history
- Merge commit / PR: fast-forward of local `main` from `99756fe` to `7173dca` (not pushed)
- History: [pixel-launcher-surface.history.md](pixel-launcher-surface.history.md)
- Closed: `2026-11-05`

## Goal and scope

Stop the live wallpaper from fighting Pixel Launcher on the Pixel 7a and Pixel 9 Pro.

## What landed

- Frames post only while the wallpaper is visible and zoom is idle. Colors are a stable black theme hint.
- The 1 Hz partial canvas update and `BedtimeCountdownService` are gone. Bedtime is a chronometer notification plus an inexact idle alarm.
- One shared calendar observer. Location is stored while the activity is resumed. Background location, exact-alarm, and foreground-service permissions are removed.
- The dial uses status-bar height and density instead of raw 150 px and 80 px offsets.

## Validation

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` passed (`pixel-launcher-android`).
- Device install is recorded with the close, not in this archive's pre-install note.

## Disposition

- Status: merged into `main` (fast-forward)
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes
- Cleanup completed: yes
