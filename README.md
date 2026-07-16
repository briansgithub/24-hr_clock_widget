# 24-Hour Clock Hub

A monorepo containing a 24-hour analog clock implemented as both an **Android Live Wallpaper** and a **Python Desktop Widget**. Both versions feature deep Fitbit integration and a research-backed "Two-Process Model of Alertness" to visualize your circadian rhythm and energy levels.

## Repository Structure

-   **/android**: The Android application project (Kotlin/Compose).
-   **/python**: The Python desktop widget application.

---

## Getting Started: Secret Configuration

To protect privacy and API keys, this project uses modular configuration files that are ignored by version control. You **must** create these files locally to enable health and calendar integrations.

### 1. Fitbit Integration (Required for both)
You need to register a "Personal" application on [dev.fitbit.com](https://dev.fitbit.com) to access your heart rate and sleep data.

-   **Android Setup**:
    1.  Open `local.properties` at the project root (see `local.properties.example`).
    2.  Add your credentials:
        ```properties
        FITBIT_CLIENT_ID=your_id
        FITBIT_CLIENT_SECRET=your_secret
        ```
-   **Python Setup**:
    1.  Create `python/fitbit_config.json` (see `python/fitbit_config.json.example`).
    2.  Add your credentials:
        ```json
        {
          "FITBIT_CLIENT_ID": "your_id",
          "FITBIT_CLIENT_SECRET": "your_secret"
        }
        ```

### 2. Google Calendar Integration (Optional for Widget)
-   Create a "Desktop Application" client ID in the [Google Cloud Console](https://console.cloud.google.com/).
-   Download the JSON and save it as `python/credentials.json` (see `python/credentials.json.example`).

---

## Local Setup

### Android App
1.  Open the root folder in Android Studio.
2.  Ensure your `local.properties` is populated (as shown above).
3.  Build and deploy to your device.

### Python Widget
1.  Navigate to the `python/` directory.
2.  Install dependencies: `pip install -r requirements.txt`.
3.  Ensure `fitbit_config.json` (and optionally `credentials.json`) are present in the `python/` folder.
4.  Run the app: `python clock_widget.py`.

---

## Documentation
For detailed information on the math and features of each component, see:
-   [Android Documentation](android/README.md)
-   [Python Documentation](python/README.md)
-   [Energy Logic & Math](python/ENERGY_LOGIC_README.md)
