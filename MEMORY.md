# Memory: 24-Hour Clock Project Hub

## Project Consolidation (2026-07-15)
The Android and Python projects were unified into a Monorepo to simplify parallel development and shared logic tracking.

## Feature History

### Streamlining Auth & Sync Reconnection
External service authentication (Fitbit & Google Calendar) had friction points:
1.  **Python Widget:** Background refreshes would trigger jarring automatic browser popups on token failure.
2.  **Android App:** Background sync failures were silent, leading to stale data until the user manually checked the app.
3.  **General:** Reconnecting required too many taps/navigation steps.

#### Architectural Solutions
- **"1-Tap or Less" Re-authentication**: Android uses notifications; Python uses clickable on-clock icons.
- **Asynchronous Error Signaling**: Introduced `ReauthRequiredError` and DataStore flags to prevent UI blocking.
- **Proactive UI Feedback**: Added error banners and status indicators.

### Proactive Data Refreshing
- **Interaction Triggers**: Refresh on Android unlock or Python widget hover.
- **Aggressive Polling**: Standardized on 10-minute polling intervals for all data.

## Bug Fixes & Stability (2026-07-16)
- **Resolved Notification Crash**: Fixed an unresolved reference to `setSmallResource` by correcting it to `setSmallIcon` in `AuthNotificationManager`.
- **Restored WorkManager**: Added the missing `androidx.work:work-runtime-ktx` dependency, resolving multiple compilation errors in the background sync logic (`SyncManager` and `SyncWorker`).
- **Git History Scrubbed**: Performed a full repository history rewrite using `git filter-branch` to remove sensitive OAuth credentials and tokens from all past commits. Updated `.gitignore` to prevent future tracking of these files.

## Secret Handling & Modularization (2026-07-16)
To prevent accidental leaks of sensitive information, the following modularization was implemented:

### 1. Android Secrets (Fitbit)
- **Previous state**: `clientId` and `clientSecret` were hardcoded in `FitbitManager.kt`.
- **Current state**:
    - Secrets are stored in `local.properties` (ignored by Git) as `FITBIT_CLIENT_ID` and `FITBIT_CLIENT_SECRET`.
    - `build.gradle.kts` loads these values and exposes them via `BuildConfig`.
    - `FitbitManager.kt` references `BuildConfig.FITBIT_CLIENT_ID` and `BuildConfig.FITBIT_CLIENT_SECRET`.

### 2. Python Secrets (Fitbit)
- **Previous state**: `FITBIT_CLIENT_ID` and `FITBIT_CLIENT_SECRET` were hardcoded in `clock_widget.py`.
- **Current state**:
    - Secrets are loaded from `python/fitbit_config.json` (ignored by Git).
    - A template `python/fitbit_config.json.example` is provided for setup.
    - `ClockWidget` class handles loading these at runtime.

### 3. File Isolation
- Updated `.gitignore` to use more aggressive wildcard matching (`*.json`) while whitelisting non-sensitive config files (e.g., `package.json`, `google-services.json`). This ensures that token caches and local setting files are never accidentally committed.
