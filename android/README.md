# 24-Hour Clock Android Wallpaper

An Android live wallpaper that renders a 24-hour analog clock with circadian rhythm data, solar/lunar positions, and health metrics.

## Recent Features

### Clock planning

- Open **Plan** and use the table below the clock. Fill the **New task** row with a name, a start time using the 12-hour AM/PM clock dial (hours first, then minutes), and a duration using the hour/minute clock dials (one minute to 24 hours, with a separate **24 hours** option), then tap **+**. The next row's start time advances to the previous task's end, including across midnight.
- Edit the name directly, tap **Start** to select a time, or tap **Dur.** to select hours and minutes, then choose **Save** or **Cancel**. Start displays `h:mm AM/PM`; duration displays `h:mm`. Zero-duration tasks cannot be saved; the trash button removes a task. Clock dragging refreshes the start cell while preserving unsaved name/duration edits.
- Each saved task gets a random color that stays with it. Its wedge spans the task's duration on the daily clock, including across midnight.
- Drag a wedge around the clock to change its start time while keeping its point at the center. Its duration stays fixed. When tasks overlap, the last task in the list is on top and receives the drag.
- Wedges appear above the clock, sleep, grogginess, and wind-down layers, below the now hand. The Plan editor always shows tasks; **Display → Home Screen / Lock Screen → Show Planned Tasks** controls their visibility in previews and wallpaper independently.
- Tasks are saved locally and survive app restarts and display-setting resets. Wallpaper dragging depends on the launcher forwarding touch gestures; the Plan editor supports dragging directly.

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
