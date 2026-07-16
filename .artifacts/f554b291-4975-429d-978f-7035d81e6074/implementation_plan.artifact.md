# Implementation Plan - Bedtime Notifications

Implement two types of notifications based on previous night's sleep data:
1.  **Bedtime Reminder**: Alerts the user at a calculated bedtime (1.5 hours before previous night's fall-asleep time, rounded to the nearest 5 minutes).
2.  **Bedtime Countdown**: A persistent notification counting down to the calculated bedtime.

## Proposed Changes

### [Component Name] Android Logic & Notifications

#### [NEW] [BedtimeNotificationManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeNotificationManager.kt)
- Create a manager class to handle notification logic.
- Methods:
    - `calculateBedtime(logs: List<SleepLogEntry>): Long?`: Extracts the last main sleep start time, subtracts 1.5 hours, rounds to nearest 5 mins, and returns the next occurrence of this time.
    - `updateNotifications()`: Fetches logs, calculates bedtime, schedules the reminder alarm, and starts/updates the persistent notification.
    - `createNotificationChannels()`: Sets up the two channels with appropriate importance.

#### [NEW] [BedtimeReminderReceiver.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeReminderReceiver.kt)
- A `BroadcastReceiver` to handle the `AlarmManager` trigger.
- When triggered, it shows the "Time for bed [time]" notification.

#### [NEW] [BedtimeCountdownService.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeCountdownService.kt)
- A `Foreground Service` to maintain the persistent countdown notification.
- Uses `setUsesChronometer(true)` and `setChronometerCountDown(true)` to minimize battery usage while showing a live countdown.

#### [MODIFY] [MainActivity.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/MainActivity.kt)
- Request `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM` permissions.
- Initialize `BedtimeNotificationManager` and call `updateNotifications()` on start and after sync.

#### [MODIFY] [SyncWorker.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/SyncWorker.kt)
- Call `BedtimeNotificationManager(context).updateNotifications()` after `refreshMetrics()` to ensure notifications are up-to-date with new Fitbit data.

#### [MODIFY] [AndroidManifest.xml](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/AndroidManifest.xml)
- Add permissions:
    - `android.permission.POST_NOTIFICATIONS`
    - `android.permission.SCHEDULE_EXACT_ALARM`
    - `android.permission.USE_EXACT_ALARM` (depending on API target)
    - `android.permission.FOREGROUND_SERVICE`
    - `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` (or similar for Android 14+)
    - `android.permission.RECEIVE_BOOT_COMPLETED`
- Register `BedtimeReminderReceiver` and `BedtimeCountdownService`.

## Verification Plan

### Automated Tests
- N/A (Manual verification is more effective for notifications/alarms in this context).

### Manual Verification
1.  **Login to Fitbit**: Ensure sleep logs are fetched.
2.  **Verify Calculation**: Check logs for previous sleep time and verify the calculated bedtime matches the "1.5h before" logic.
3.  **Check Notifications**:
    - Verify the persistent notification appears with a countdown.
    - Verify the reminder notification fires at the correct time (can be tested by mocking data or waiting).
4.  **Check Toggles**: Go to System Settings -> Notifications for the app and verify two channels exist and can be toggled independently.

Please review the plan above. Reply with 'Proceed' to begin implementation or provide feedback.
