# Streamlining Auth and Sync for Fitbit & Google Calendar

This plan aims to reduce friction when reconnecting to external services (Fitbit and Google Calendar) across both the Android app and the Python widget.

## User Review Required

> [!IMPORTANT]
> For the Python widget, I propose moving away from "Automatic browser popups on failure" in favor of a "User-triggered re-auth" via an icon in the widget. This prevents unexpected browser windows from appearing during background refreshes.

## Proposed Changes

### [Component] Android App (Kotlin)

#### [MODIFY] [FitbitManager.kt](file:///H:/Desktop/widgets/24_hr_clock_android/app/src/main/java/com/example/a24_hr_clock/logic/FitbitManager.kt)
- Add a `reauthRequiredFlow` to track if tokens are dead.
- Update `refreshTokens()` to set this flag if it fails with a 401/400.

#### [MODIFY] [MainActivity.kt](file:///H:/Desktop/widgets/24_hr_clock_android/app/src/main/java/com/example/a24_hr_clock/MainActivity.kt)
- Observe `reauthRequiredFlow`.
- In the `Sleep` tab, show a high-visibility warning if re-auth is needed.
- In the `Preview` screen, add a small subtle indicator (e.g., a tinted icon) if sync is failing.

#### [NEW] [AuthNotificationManager.kt](file:///H:/Desktop/widgets/24_hr_clock_android/app/src/main/java/com/example/a24_hr_clock/logic/AuthNotificationManager.kt)
- A helper to show a system notification when background sync fails due to auth, allowing the user to tap to open the re-auth screen.

---

### [Component] Python Widget (Python)

#### [MODIFY] [fitbit_client.py](file:///H:/Desktop/widgets/24-hr_clock_widget/fitbit_client.py)
- Change `_request_with_refresh` to raise a specific `ReauthRequiredError` instead of calling `authorize()` directly.
- Add an `is_authenticated` property.

#### [MODIFY] [google_calendar_client.py](file:///H:/Desktop/widgets/24-hr_clock_widget/google_calendar_client.py)
- Update `get_calendar_service` to allow a "check only" mode that doesn't pop the browser.
- Raise `ReauthRequiredError` on refresh failure.

#### [MODIFY] [clock_widget.py](file:///H:/Desktop/widgets/24-hr_clock_widget/clock_widget.py)
- Add a "Sync Status" indicator to the UI.
- Use a non-blocking thread to check for `ReauthRequiredError`.
- If an error is caught, update the UI to show a "Reconnect" button/icon that the user must click to open the browser.

---

### [Component] Project Documentation

#### [NEW] [MEMORY.md](file:///H:/Desktop/widgets/24_hr_clock_android/MEMORY.md)
- Record the friction analysis and the architectural changes made to streamline the process.

## Verification Plan

### Automated Tests
- For Android: Mock a 401 response and verify the re-auth flag is set in DataStore.
- For Python: Unit test the `FitbitClient` to ensure it raises `ReauthRequiredError` when refresh fails.

### Manual Verification
- **Android:** Revoke the app's access on the Fitbit dashboard, run the app, and verify the "Re-auth Required" UI and notification appear.
- **Python:** Manually delete `token.json` or `fitbit_tokens.json` and verify the widget shows a "Sync Error" icon instead of immediately opening the browser.
