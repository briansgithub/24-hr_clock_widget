# Branch: `feat/sleep-stage-curve`

## Objective and scope

Add a toggleable "Show Sleep Stages" display element: a smooth polar curve of the night's Fitbit/Google Health sleep stages inside the sleep wedge. Home and Lock Screen switches are independent and default off.

## Identity and decisions

- Base: `feat/sleep-awake-markers` (190a726). Merging that branch to main first is owner-approved; blocked on GitHub credentials (see Next action).
- Stages deep/light/REM/wake sit at 0.30/0.50/0.65/0.85 of the dial radius (deep toward centre, wake at the wedge rim). Wakes under 3 minutes fold into the previous stage. The curve is a triangular moving average (±6 min), so it stays within the real levels.
- Stored per log as compact `stages` (newest 3 nights, same trim as awake markers). Shown only in clock-time (not time-asleep) wedge mode, like the awake markers. No stages: no curve, wedge unchanged.
- Owned paths: SleepStages.kt, SleepStagesTest.kt, parsers, ClockRenderer, settings, glyph.

## Validation checkpoint — 2026-11-22

- `gradlew :app:testDebugUnitTest :app:assembleDebug` passed (full unit suite incl. SleepStagesTest).
- Installed with `adb install -r` on the Pixel 9 Pro; screenshot with real Google Health data inspected: curve shows inside the wedge.

## Next action

Push, PR, merge and deploy need GitHub credentials (`gh` is not logged in).
