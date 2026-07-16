# Memory: Streamlining Auth & Sync Reconnection

## Status: Implemented (2026-07-15)

### Problem
External service authentication (Fitbit & Google Calendar) had friction points:
1.  **Python Widget:** Background refreshes would trigger jarring automatic browser popups on token failure.
2.  **Android App:** Background sync failures were silent, leading to stale data until the user manually checked the app.
3.  **General:** Reconnecting required too many taps/navigation steps.

### Architectural Solutions

#### 1. "1-Tap or Less" Re-authentication
- **Android:** Implemented a system notification for background sync failures. Tapping the notification opens `MainActivity`, which immediately launches the Fitbit OAuth flow via Custom Tabs.
- **Python:** Replaced automatic browser popups with a non-blocking UI state. A persistent warning icon ("F" for Fitbit, "C" for Calendar) appears on the clock face during failures. Clicking the icon triggers the OAuth flow.

#### 2. asynchronous Error Signaling
- **Python:** Introduced `ReauthRequiredError` in `fitbit_client.py` and `google_calendar_client.py`. Background threads now catch these errors and signal the main UI thread to show warnings instead of interrupting the user.
- **Android:** Added `reauthRequired` flag to DataStore. `FitbitManager` detects 401/400 errors during token refresh and sets this flag. `MainActivity` and `SyncWorker` observe this flag to show UI banners and system notifications respectively.

#### 3. Proactive UI Feedback
- Added an error banner to the top of `MainActivity` that appears whenever `reauthRequired` is true, providing a clear path to reconnection from any screen in the app.
- Added tooltip-ready icons on the Python widget face to ensure the user is aware of sync status without needing to open the controls window.

### Files Modified
- **Android:** `FitbitManager.kt`, `MainActivity.kt`, `SyncWorker.kt`, `AuthNotificationManager.kt` (New), `AndroidManifest.xml`.
- **Python:** `fitbit_client.py`, `google_calendar_client.py`, `clock_widget.py`.
