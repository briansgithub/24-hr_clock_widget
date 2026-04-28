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
- **Sleep Debt Factor**: Toggle the impact of your rolling 14-day sleep debt on your energy levels.
- **Normalization**: Switch between an absolute "Alertness" view and a normalized view that fills the clock face for better visibility.

## File Overview

### [clock_widget.py](clock_widget.py)
The main application script. Manages the GUI, handles user interactions, and orchestrates background data updates from the Fitbit API.

### [energy_logic.py](energy_logic.py)
The core mathematical engine for the energy curve. It implements a **Two-Process Model of Alertness** (Borbély):
- **Process C (Circadian)**: Driven by your Fitbit-detected **Bathyphase** (lowest heart rate point) to accurately map your natural peaks and dips.
- **Process S (Homeostatic)**: Models sleep pressure build-up based on your actual sleep duration and accumulated debt.
- **Rendering**: Generates a smooth gradient path representing your predicted alertness.

### [fitbit_client.py](fitbit_client.py)
A specialized client for the Fitbit Web API (OAuth 2.0).
- **Automated OAuth**: Handles browser-based authorization and token refresh.
- **Pragmatic Caching**: Uses a modular 3-file caching system to ensure instant startup performance:
  - `fitbit_summary.json`: Lightweight calculated values for UI rendering.
  - `fitbit_sleep_logs.json`: Raw logs for sleep debt calculation.
  - `fitbit_hr_intraday.json`: High-resolution heart rate data for bathyphase detection.
- **Smart Fallback**: Detects if today's sync is missing and provides a fallback to the most recent data without corrupting the permanent cache.

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
