# Python Widgets: 24-Hour Clock & Energy Tracker

A collection of desktop widgets built with Python and Tkinter, featuring a unique 24-hour analog clock with deep Fitbit integration and circadian rhythm tracking.

## Features

### 24-Hour Analog Clock
- **Single-Hand Design**: A unique 24-hour hand that maps to the natural rotation of the day.
- **Dynamic Shading**: Automatically calculates and shades the night region (sunset to sunrise) using local coordinates.
- **Transparency Engine**: The widget becomes semi-transparent and hides controls when idle, and becomes solid with interactive toggles on hover.
- **System Tray**: Runs in the background with a custom tray icon for easy window management.

### Fitbit Integration & Sleep Tracking
- **Sleep Arc**: Visualizes your last night's sleep directly on the clock face. 
- **Bedtime Mode**: Toggle between "Time in Bed" (total window) vs "Actual Sleep Time" (minus wake-ups) to see how efficient your rest was.
- **Circadian Energy Curve**: A color-coded gradient (Cyan to Red) that visualizes your alertness throughout the day.
- **Weighted Sleep Debt**: Models the impact of your last 14 days of sleep with a 90% daily decay factor, making recent rest (and naps) more influential.
- **Normalization**: Switch between an absolute "Alertness" view and a normalized view that fills the clock face for better visibility.
- **Energy Percentage**: Displays your current alertness as a percentage (0-100%) at the tip of the clock hand.
    - **100%** represents your theoretical peak alertness today if you were fully rested (0 sleep debt and 9.75 hours of sleep).
    - **0%** represents the floor of the Two-Process model, typically reached during your circadian trough (3-5 AM) or under extreme sleep deprivation.
    - **Toggle Sensitivity**: The percentage respects the "Factor in Sleep Debt" and "Include Naps" toggles but remains absolute regardless of the "Normalize Energy" setting.

## File Overview

### [clock_widget.py](clock_widget.py)
The main application script. Manages the GUI, handles user interactions, and orchestrates background data updates from the Fitbit API.

### [energy_logic.py](energy_logic.py)
The core mathematical engine for the energy curve. It implements a **Two-Process Model of Alertness** (Borbély):
- **Process C (Circadian)**: Driven by your Fitbit-detected **Bathyphase** (lowest heart rate point) to accurately map your natural peaks and dips.
- **Process S (Homeostatic)**: Models sleep pressure build-up based on your actual sleep duration and accumulated debt.
- **Rendering**: Generates a smooth gradient path representing your predicted alertness.
- **Weighted Sleep Debt & Nap Logic**:
    - **Recency Bias**: Recent nights are significantly more impactful. A nap taken yesterday is ~4.3x more effective at reducing your debt penalty than a nap taken 14 days ago (90% daily decay).
    - **Nap Summing**: Multiple sleep sessions (naps) on the same date are grouped together to calculate the total daily rest against your personal goal.
    - **Dynamic Sensitivity**: Uses a high-sensitivity divisor (25.0) to ensure consistent deficits are visible while allowing for rapid recovery after a "catch-up" nap.

### [fitbit_client.py](fitbit_client.py)
A specialized client for the Fitbit Web API (OAuth 2.0).
- **Automated OAuth**: Handles browser-based authorization and token refresh.
- **Pragmatic Caching**: Uses a modular 3-file caching system to ensure instant startup performance:
  - `fitbit_summary.json`: Lightweight calculated values for UI rendering.
  - `fitbit_sleep_logs.json`: Raw logs for sleep debt calculation.
  - `fitbit_hr_intraday.json`: High-resolution heart rate data for bathyphase detection.
- **Smart Fallback**: Detects if today's sync is missing and provides a fallback to the most recent data without corrupting the permanent cache.

## Smart Sync & Caching
The application is designed to be highly responsive while minimizing API usage:

- **Instant Startup**: If the app has already found today's sleep record, it loads the UI instantly from `fitbit_summary.json` without any network calls.
- **Fast Toggles**: Changing "Include Naps" or "Factor in Sleep Debt" is instant. The app recalculates the math locally using cached 14-day sleep logs.
- **Polling (1-Hour Rule)**: If you haven't synced your Fitbit yet today, the app uses "Fallback" data (yesterday's wake-up) to show you a curve. It will then intelligently check Fitbit **every 1 hour** in the background until your real today's sleep appears.
- **Bathyphase Locking**: Once today's heart rate minimum (bathyphase) is found, it is cached for the rest of the day to save heart rate API quota.

### Intraday HR Cache (`fitbit_hr_intraday.json`)
The high-resolution heart rate cache is created only when specific conditions are met to avoid unnecessary data bloat and API rate-limiting:
- **Condition 1 (Today's Sleep):** The file is only generated when a "real" sleep record for the current calendar date is found. If the app is in "Fallback" mode (using yesterday's data), it will not create or update this file.
- **Condition 2 (Successful Fetch):** It requires a successful fetch from Fitbit's intraday heart rate API.
- **Condition 3 (App Type):** Your Fitbit Developer app **must** be set to `Personal` type on [dev.fitbit.com](https://dev.fitbit.com). `Server` or `Client` types are restricted from accessing intraday data for privacy reasons.

### API Call Conditions
| Condition | Action |
| :--- | :--- |
| First launch of the day | **API Call** (Sleep Range) |
| "Include Naps" toggled | **NO API CALL** (Uses Cache) |
| Today's sleep not yet found | **API Call every 1 hour** until found |
| Today's sleep already found | **NO API CALLS** for the rest of the day |
| Midnight passes | **API Call** (Reset for new day) |

### Real-World Example: Morning Sync
1. **1:00 AM (Before Sleep)**: You open the app. No record for "today" exists yet. The app uses yesterday's wake-up as **Fallback** data and caches it with `is_real_today: False`.
2. **2:00 AM - 9:00 AM**: You are asleep. The app remains closed or idle.
3. **10:00 AM (After Wake-up)**: You sync your Fitbit and open the app.
   - The app sees the cache is "Fallback" and > 1 hour old.
   - It performs a **fresh API fetch**, discovers your new 9:30 AM wake-up time, and calculates your bathyphase.
   - It saves the new data with `is_real_today: True` and creates the `fitbit_hr_intraday.json` cache.
4. **Rest of the Day**: Any further opens or toggle changes are **instant** and use **Zero API calls**.

## Setup & Requirements

### Dependencies
- Python 3.10+
- `Pillow` (PIL), `pystray`, `requests`
- Tkinter (standard with Python)

### Fitbit API Configuration
To use the heart rate features (Bathyphase), you must register your application on [dev.fitbit.com](https://dev.fitbit.com):
1.  **Application Type**: Set to `Personal`.
2.  **OAuth Scopes**: Ensure `sleep` and `heartrate` are enabled.
3.  **Redirect URI**: Set to `http://localhost:8080`.

Update the `FITBIT_CLIENT_ID` and `FITBIT_CLIENT_SECRET` in `clock_widget.py` with your credentials.
