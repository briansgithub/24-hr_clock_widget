# Python Widgets: 24-Hour Clock & Energy Tracker

A collection of desktop widgets built with Python and Tkinter, featuring a unique 24-hour analog clock with Fitbit integration.

## File Overview

### [clock_widget.py](clock_widget.py)
The main application script. It manages the graphical user interface (GUI) for the 24-hour clock. Key features include:
- **Dynamic Shading**: Automatically calculates and shades the night region based on local sunrise/sunset times.
- **Fitbit Integration**: Displays your actual sleep window as a shaded arc on the clock face.
- **UI Behavior**: Supports "Always on Top", auto-transparency on hover, and system tray integration for background running.

### [energy_logic.py](energy_logic.py)
The core mathematical engine for the energy curve. It uses a **Two-Process Model** (Circadian Rhythm + Homeostatic Sleep Pressure) to approximate daily energy levels, similar to the RISE app.
- **Process C**: Simulates morning peaks, afternoon dips, and evening "second winds".
- **Process S**: Models the natural decay of alertness throughout the day.
- **Rendering**: Draws a color-coded gradient curve (Blue to Red) mapped to the clock's radius.

### [fitbit_client.py](fitbit_client.py)
A specialized client for interacting with the Fitbit API.
- Handles OAuth2 authentication and token management.
- Specifically retrieves "main sleep" events to determine wake and sleep hours for the clock UI.

### [fitbit_tokens.json](fitbit_tokens.json) & [fitbit_sleep_cache.json](fitbit_sleep_cache.json)
Persistence files used by the `FitbitClient`:
- `fitbit_tokens.json`: Securely stores access and refresh tokens.
- `fitbit_sleep_cache.json`: Caches the most recent sleep data to ensure the widget remains functional when offline or when API limits are reached.

## Requirements
- Python 3.10+
- `tkinter`, `PIL` (Pillow), `pystray`, `astral`, `requests`
