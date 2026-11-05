# History: `fix/pixel-launcher-surface`

Append-only milestones. Current state is in [pixel-launcher-surface.md](pixel-launcher-surface.md).

### 2026-11-05 — Pixel Launcher surface fixes

- Base: local `main` at `99756fe` after the sleep-sync fast-forward and the Aug 9 energy-refit merge.
- Wallpaper draws only while visible and zoom-idle, reports stable black wallpaper colors, and sizes the dial from density and the status bar.
- Sleep sync, GPS, and the calendar observer no longer start once per wallpaper engine. The bedtime countdown is a chronometer notification with an inexact idle alarm.
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` passed (`pixel-launcher-android`).

### 2026-11-05 — Fast-forwarded into local main

- Final tip: `f8f4549`. Local `main` fast-forwarded from `99756fe`. Not pushed.
- Handoff archived after the debug build was produced for install.
