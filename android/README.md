# 24-Hour Clock Android Wallpaper

An Android live wallpaper that renders a 24-hour analog clock with circadian rhythm data, solar/lunar positions, and health metrics.

## Recent Features

### 2024 Energy Model Update
The alertness model has been overhauled to align with the latest 2024 sleep research.
- **Updated Constants**: Uses modern time constants ($\tau_{wake}$ = 23.0h) for improved prediction accuracy.
- **High-Precision Bathyphase**: Uses a parabolic fit on heart rate data to identify your circadian nadir with minute-level precision.
- **Acrophase Indicator**: Displays an inward-pointing triangle at your predicted peak alertness point, derived by scanning your personalized model.

### Wake-up Correlation & Timezone Discovery
The wallpaper now features a detailed informational overlay in the bottom-left corner that helps contextualize your sleep patterns relative to solar cycles.

- **Wake-up Offset**: Displays the difference between your actual wake-up time (from Fitbit) and the local sunrise.
- **Correlated Timezone**: Dynamically identifies the global timezone where sunrise currently coincides with your wake-up time.
- **Target Location**: Maps the correlated timezone to the most populous city or country in that region (e.g., "New York City, NY" or "Tokyo, Japan").
- **Local Solar Info**: Displays local Sunrise and Sunset times in a 12-hour format.

### UI Improvements
- **Tabular Alignment**: Informational labels and values are vertically aligned for a clean, professional look.
- **High Visibility**: Optimized for wallpaper use with bold, size 50f typography.
- **OLED Optimized**: Maintains a pure black background to maximize battery efficiency on OLED screens.

## Technical Details

- **Language**: Kotlin
- **Rendering**: Canvas API
- **Data Sources**:
    - **Fitbit API**: For sleep logs and heart rate data.
    - **SunCalc**: For precise celestial position and solar event calculations.
    - **Google Play Services Location**: For accurate sunrise/sunset calculations based on device position.
- **Architecture**: Service-based wallpaper with custom rendering logic and Coroutine-powered data synchronization.

## Local Setup

The app requires Fitbit API credentials to function. These are loaded from `local.properties` (not tracked in Git).

1.  Follow the **Secret Configuration** steps in the [Root README](../README.md).
2.  Build the project in Android Studio. The keys will be automatically injected via `BuildConfig`.
