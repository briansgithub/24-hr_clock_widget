# Archived: `fix/bedtime-countdown-sleep-default`

## Identity

- Final tip: `afa2b4e` — docs: record bedtime fix tip a355eda
- Merge commit / PR: `e27f695` (local merge; not pushed)
- History: [bedtime-countdown-sleep-default.history.md](bedtime-countdown-sleep-default.history.md)
- Closed: `2026-07-18`

## Goal and scope

Scope the bedtime countdown 10pm clamp to fall-asleep in [22:00, 23:30], and fall back to next 10:00 PM only when no main sleep log exists.

## What landed

- Fall-asleep − 90 minutes (+ 5-minute round) as the primary target
- Clamp to 22:00 only when fall-asleep is in [22:00, 23:30]
- No-main-sleep → next 22:00; wallpaper/notification share non-null `resolveBedtimeMillis`
- `BedtimeNotificationManagerTest` unit coverage

## Validation

- `.\gradlew.bat :app:testDebugUnitTest --tests com.example.a24_hr_clock.logic.BedtimeNotificationManagerTest` — BUILD SUCCESSFUL
- `python scripts/validate_handoffs.py` passed
- Device visual check on Pixel 7a: owner may still verify post-merge on `main`

## Disposition

- Status: merged into main
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes (after worktree remove)
- Cleanup completed: yes
