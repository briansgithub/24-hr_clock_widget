# 24-hr Clock Hub: Development Summary

This document outlines the recent objectives, architectural choices, and implementations for the 24-hr Clock project, focusing on sleep hygiene, atmospheric rendering, and energy modeling.

## 1. Sleep Hygiene & Bedtime Notifications
The primary goal was to create a proactive system that helps the user maintain a consistent sleep schedule based on their actual physiological data from Fitbit.

### Objectives:
- **Smart Bedtime Calculation**: Derive the "ideal" bedtime from the previous night's main sleep fall-asleep time (minus 1.5 hours, rounded to the nearest 5 minutes).
- **Persistent Awareness**: Provide a constant countdown in the notification shade.
- **Urgent Reminders**: Send a high-priority alert when it's actually time for bed.

### Implementation:
- **`BedtimeNotificationManager`**: Centralizes the calculation logic. Ensures the target is always future-facing to prevent "timer overflow" issues.
- **`BedtimeCountdownService`**: A Foreground Service using a custom `RemoteViews` layout.
    - **Visual Progression**: The countdown text fades from **Bright White** to **Gray** as bedtime approaches.
    - **Overdue State**: Once bedtime passes, it displays **"GO TO BED!"** for a 6-hour window before reset.
- **System Integration**: Notifications are categorized into distinct channels allowing independent system-level toggling.

---

## 2. Grogginess Layer (Sleep Inertia Visualization)
Added a visual layer to the clock face to represent the transition period between sleep and full alertness.

### Objectives:
- **Contextual Visualization**: Show a distinct "Grogginess" period on the clock face following a main sleep session.
- **Consistency**: Use the same Fitbit-derived wake-up logic as the energy model.

### Implementation:
- **Rendering**: A **solid light gray wedge** (`#60BBBBBB`) spanning **1.5 hours** immediately after the detected wake-up time.
- **UI Control**: Added a "Grogginess Wedge" toggle in the Display Settings.
- **Smart Defaults**: Enabled by default on the Home Screen (for morning planning) and disabled on the Lock Screen (for minimalist aesthetics).

---

## 3. Dynamic Sun Appearance (Atmospheric Logic)
Enhanced the "Sun and Moon" feature to reflect atmospheric conditions based on altitude.

### Objectives:
- **Realism**: Transition the sun's color from bright yellow at noon to deep orange/red at the horizon.
- **Night Navigation**: Keep the sun visible as a faint yellow icon at night to indicate its position relative to the nadir.

### Implementation:
- **Elevation-Based Interpolation**: Logic in `ClockRenderer.kt` (Android) and `clock_widget.py` (Python) calculates sun color and alpha based on its degrees above/below the horizon.
- **Twilight Transition**: Alpha scales up as the sun approaches the horizon from below, simulating the onset of golden hour.

---

## 4. Empirical Energy Modeling Refinements
Optimized the system for logging and analyzing subjective energy levels.

### Objectives:
- **Performance**: Minimize DataStore I/O during background syncs.
- **Workflow**: Streamline the "Alertness Check" notifications and the history viewing experience.

### Implementation:
- **Data Optimization**: Decoupled `ModelSettings` from the core logging loop to reduce redundant reads.
- **History UI**: Refined the `EmpiricalLogHistoryScreen` with better date grouping, clearer status indicators ("Logged" vs "Missed"), and enhanced Google Drive/Local backup configurations.

---

## Technical Debt & Fixes
- **Chronometer Fix**: Resolved an issue where negative hour offsets were shown by ensuring the `Chronometer` is always given a future target and using the `android:countDown` attribute correctly.
- **Build Stabilization**: Fixed several unresolved reference and coroutine scope errors in the Compose UI layers.
