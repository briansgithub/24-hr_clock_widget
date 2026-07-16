# Bedtime Notifications Implementation Walkthrough

I have implemented two new bedtime notification features: a **reminder alert** and a **persistent countdown**. These notifications are based on the previous night's fall-asleep time (calculated as 1.5 hours before the previous main sleep, rounded to the nearest 5 minutes).

## Key Components

### [BedtimeNotificationManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeNotificationManager.kt)
- **Logic**: Extracts the last main sleep start time from Fitbit logs.
- **Calculation**: Subtracts 90 minutes and rounds to the nearest 5-minute mark.
- **Orchestration**: Schedules an exact alarm via `AlarmManager` and starts the countdown foreground service.
- **Channels**: Creates two distinct notification channels:
    - `Bedtime Reminder` (High Importance)
    - `Bedtime Countdown` (Low Importance, Persistent)

### [BedtimeCountdownService.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeCountdownService.kt)
- **Persistent Countdown**: A Foreground Service that displays a live countdown to the target bedtime.
- **Efficiency**: Leverages the system chronometer (`setChronometerCountDown(true)`) to minimize wakeups while maintaining an accurate live timer in the notification shade.

### [BedtimeReminderReceiver.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/BedtimeReminderReceiver.kt)
- **Alert**: Triggered by `AlarmManager` exactly at the calculated bedtime (1.5h before previous night's sleep).
- **Notification**: Shows "Time for bed" along with the target time.

### [MainActivity.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/MainActivity.kt) & [SyncWorker.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/SyncWorker.kt)
- **Permissions**: Now requests `POST_NOTIFICATIONS` on Android 13+.
- **Lifecycle**: Notifications are updated automatically on app launch and after every background Fitbit sync.

## Toggling Notifications
The two types of notifications can be toggled independently in the Android System Settings:
1. Long-press the app icon -> **App info**.
2. Tap **Notifications**.
3. Toggle **Bedtime Reminder** or **Bedtime Countdown** as desired.

## Verification
- [x] Bedtime calculation logic (1.5h offset + 5m rounding).
- [x] Persistent countdown service initialization.
- [x] Alarm scheduling for reminder.
- [x] Notification channel separation.
