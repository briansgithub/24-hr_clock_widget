# Implementation Plan - Bedtime Notification Robustness

Refine the bedtime notification logic to ensure the countdown timer is always valid and the "GO TO BED!" message persists for a 6-hour window after the target passes.

## Proposed Changes

### [Component Name] Android App Logic

#### [MODIFY] [BedtimeNotificationManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeNotificationManager.kt)
- Update `calculateBedtimeMillis` to always return the **next future occurrence** of the bedtime.
- Change the logic from `target.isBefore(now.minusHours(4))` to simply `target.isBefore(now)`. If the target is in the past, add one day.

#### [MODIFY] [BedtimeCountdownService.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeCountdownService.kt)
- Update `createNotification` to handle the "late" state relative to the *previous* occurrence.
- Define `prevBedtimeMillis = currentBedtimeMillis - 24 hours`.
- Set `isOverdue = now >= prevBedtimeMillis && now < prevBedtimeMillis + 6 hours`.
- This ensures that for 6 hours after the calculated bedtime, the "GO TO BED!" message is shown, while the `Chronometer` is prepared with a valid future target for when the window expires.

## Verification Plan

### Manual Verification
1.  **Sync Fitbit Data**: Ensure bedtime is calculated.
2.  **Test "Normal" Countdown**: Verify that before bedtime, the timer shows a valid countdown (e.g., 2h 30m).
3.  **Test "Overdue" State**:
    - Wait until bedtime passes (or mock data).
    - Verify the notification immediately switches to **"GO TO BED!"**.
    - Verify it stays in this state for up to 6 hours.
4.  **Test "Next Day" Transition**:
    - Verify that after 6 hours (or by mocking time), the notification switches back to a countdown for the *next* night (starting around 18 hours).
    - Verify no negative or "absurdly large" numbers are visible during transitions.

Please review the plan above. Reply with 'Proceed' to begin implementation or provide feedback.
