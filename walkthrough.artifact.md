# Walkthrough - Sleep Table Logic Re-implementation (Android)

The sleep table logic in the Android app has been re-implemented from the ground up to perfectly match the logic and behavior of the original Python widget.

## Key Changes

### 1. Data Model Alignment
- **`ModelSettings`**: Added `explicitDates` to track manual user overrides on sleep log inclusion. This ensures that the automatic T-0 (Today) logic doesn't fight against user choices.
- **`SettingsManager.kt`**: Updated to handle the new `explicitDates` field and provide defaults.

### 2. Algorithmic Parity
- **Circular Mean (`circAvg`)**: Implemented the circular mean algorithm in `EnergyCalculator.kt`. This allows the "AVG" row in the sleep table to correctly average start and end times (e.g., averaging 11:00 PM and 1:00 AM results in 12:00 AM, not 12:00 PM).
- **Weighted Sleep Debt**: Ensured the 15-day window (T-0 to T-14) and 0.9 decay factor are strictly followed in the Android implementation.

### 3. UI and Logic Enhancements (`SleepLogScreen`)
- **Table Layout**: Refactored the Compose-based table to use precise column weights and alignment, matching the Python widget's visual density.
- **Manual Overrides**: Checkboxes now update both `excludedDates` and `explicitDates`.
- **T-0 Auto-Logic**:
    - If Today has no log and hasn't been manually toggled, it is **auto-excluded** (to prevent unfair debt penalty).
    - As soon as a sync provides a log for Today, it is **auto-included**.
    - If the user manually toggles the checkbox, the auto-logic is bypassed.
- **Complete Summary Row**:
    - Added "AVG" row with circular mean Start/End times.
    - Added Average Duration and Efficiency.
    - Added Total Raw Debt and Total Weighted Debt.
- **Formatting**:
    - Time formatting (e.g., `10:30p`) matches the compact Python style.
    - Debt colors (Red for deficit, Green for surplus) and dimmed text for excluded rows provide clear visual feedback.

## Verification Summary
- **Static Analysis**: `analyze_file` was run on all modified files (`MainActivity.kt`, `EnergyCalculator.kt`, `SettingsManager.kt`) and returned no errors or warnings.
- **Logic Review**: The code was manually reviewed against the Python source to ensure variable names, math constants (decay factor 0.9, weights), and state transitions match exactly.
