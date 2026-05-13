# Sleep Table & Energy Logic Summary

This document outlines how the 24-hr clock widget determines which sleep log entries are displayed and how they factor into energy and sleep debt calculations.

## 1. Display Logic: What entries are shown?
*   **15-Day Window**: The sleep table targets a rolling window from **T-0 (Today)** to **T-14 (14 days ago)**. 
*   **Data Matching**: For each date in this window, the widget searches for matching logs in the raw data (synced from Fitbit). 
    *   If a log exists: Displays start/end times, duration, and efficiency.
    *   If no log exists: Displays placeholders (`—`) but keeps the row visible to allow for manual exclusion/inclusion.
*   **Visual State**: 
    *   **Excluded** (Unchecked): Rows are dimmed/greyed out.
    *   **Included** (Checked): Rows use standard high-contrast colors; debt is highlighted in red (deficit) or green (surplus).

## 2. Calculation Logic: Sleep Debt & Energy Curve
The widget uses the `excluded_dates` list to filter data passed to the `energy_logic.py` engine.

*   **Weighted Sleep Debt**: Only checked entries are factored into the total sleep debt.
    *   **Decay Factor**: Each day back is weighted at **90%** of the day following it (`0.9^days_ago`). T-0 has a weight of 1.0, T-1 has 0.9, etc.
    *   **Debt Formula**: `Nightly Debt = Sleep_Need - Actual_Sleep`.
*   **Missing Data Penalty**: If a date is **checked** but has **no log data**, the logic treats it as **0.0 hours of sleep**. This results in a maximum sleep debt penalty for that day, significantly lowering the energy curve.
*   **Table Averages**: The "AVG" row (Start, End, Duration, Efficiency) is calculated dynamically using only the data from **checked** entries.

## 3. Checkbox Interaction & Persistence
Toggling a checkbox creates an immediate feedback loop:

*   **Manual Override**: Clicking a checkbox adds/removes the date from `excluded_dates` and marks it as an "explicit" date in `sleep_settings.json`.
*   **Instant Recalculation**: Toggling triggers an immediate call to `compute_sleep_debt`, which updates:
    1.  The `sleep_debt_hours` metric.
    2.  The **Energy Curve** (shifting its amplitude and vertical offset).
    3.  All UI labels and table averages.
*   **Auto-Inclusion Logic (T-0 Safety)**:
    *   To prevent a "0.0h sleep" penalty before the user has woken up or synced, the widget **auto-excludes** Today (T-0) if no log is found.
    *   Once a log is detected, it **auto-includes** it.
    *   This automation is bypassed once a user manually toggles the checkbox for that date.

## 4. Summary of Dependencies
| Feature | Depends on Checkbox? | Behavior when Unchecked |
| :--- | :--- | :--- |
| **Table Row** | Yes (Visual only) | Text turns grey (#666666) |
| **Weighted Debt** | Yes | Date is skipped in the summation |
| **Energy Curve** | Yes | Debt component of the 2-process model is reduced |
| **Table Averages** | Yes | Data is excluded from the mean calculation |
| **Settings File** | Yes | Status is saved to `sleep_settings.json` |
