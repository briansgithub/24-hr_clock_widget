# Bedtime Notifications Implementation Walkthrough

I have implemented and refined two new bedtime notification features: a **reminder alert** and a **persistent countdown**, now with dynamic visual feedback and robust overdue logic.

## Key Components

### [BedtimeNotificationManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeNotificationManager.kt)
- **Logic**: Extracts the last main sleep start time from Fitbit logs.
- **Calculation**: Subtracts 90 minutes and rounds to the nearest 5-minute mark.
- **Robust Target Selection**: The manager now **always** returns the next future occurrence of the calculated bedtime. This ensures the countdown timer never receives a past date, preventing negative numbers or "absurdly large" hour offsets.

### [BedtimeCountdownService.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeCountdownService.kt)
- **Persistent Countdown**: A Foreground Service that displays a live countdown to the target bedtime.
- **6-Hour Overdue Window**:
    - When the calculated bedtime passes, the notification switches to a prominent **"GO TO BED!"** message.
    - This state now persists for exactly **6 hours** after the target time.
    - After the 6-hour window expires (e.g., in the morning), the notification automatically switches back to the countdown for the *next* night's bedtime.
- **Dynamic Color**:
    - The countdown text starts as **Bright White** (`#FFFFFF`).
    - As it approaches bedtime (within the last 90 minutes), the color **linearly interpolates** toward **Gray** (`#888888`).
    - The "GO TO BED!" message remains in the final gray color.

### [BedtimeReminderReceiver.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeReminderReceiver.kt)
- **Alert**: Triggered by `AlarmManager` exactly at the calculated bedtime.

## UI Refinement
- **Shortened Title**: Simply **"Bedtime"**.
- **Header Metadata**: **"Target: [time]"** in the notification header.
- **Main Content**: Live countdown timer in the body, which switches to "GO TO BED!" when appropriate.
- **Countdown Fix**: Added `android:countDown="true"` to the `Chronometer` layout and ensured the target is always in the future to maintain correct formatting.

## Verification
- [x] Bedtime calculation logic (always future-facing).
- [x] Overdue state logic (6h "GO TO BED!" window).
- [x] Color interpolation logic (White to Gray over 90 mins).
- [x] Periodic refresh functionality.
- [x] Correct countdown formatting in `Chronometer`.
