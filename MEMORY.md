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
