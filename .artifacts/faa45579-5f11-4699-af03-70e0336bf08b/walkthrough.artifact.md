# Walkthrough - Unified Sleep & Energy Logic (2024 Research Update)

I have unified the energy and sleep logic between the Android app and Python widget, updating both to reflect the latest 2024 sleep research.

## Changes Made

### 1. Research-Based Model Constants
Both apps now default to updated time constants for the 2-process model:
- **$\tau_{wake}$ (Buildup):** Increased from 18.2h to **23.0h**.
- **$\tau_{sleep}$ (Decay):** Refined from 4.2h to **4.0h**.
- **Circadian Fallback:** Standardized to a **13.0h** offset from wake (assuming a nadir 2h before wake and a 15h offset to peak).

### 2. Precise Bathyphase Detection
Implemented a **Parabolic Vertex Fit** algorithm in both `EnergyCalculator.kt` (Android) and `energy_logic.py` (Python).
- Instead of simple hourly buckets, the logic now analyzes the lowest heart rate hour and its two neighbors to find the mathematical trough with sub-hour precision.

### 3. Unified Efficiency Calculation
Refactored the `Sleep Need` calculation to be consistent across platforms:
- **Average of Ratios:** Efficiency is now the mean of individual nightly efficiencies (Asleep / Time in Bed).
- **Nap Exclusion:** Naps are strictly excluded from efficiency averages to avoid skewing baseline needs, but they still correctly subtract from cumulative sleep debt at 100% efficiency.

### 4. Personalised Acrophase Indicators
- **Python:** Added new visual indicators for **Bathyphase** (Trough) and **Acrophase** (Peak) to match the Android styling.
- **Scanning Peak:** Both apps now scan the personalized energy curve to identify the true peak for the Acrophase indicator.
- **Consistent %:** The `Energy %` metric in both apps is now calculated relative to a "Perfection" peak (0 debt, goal sleep), using the same scanning logic.

## Verification Results

### Automated Tests
- [x] **Python Bathyphase Precision:** Verified parabolic fit accuracy with `test_bathyphase.py` (Passed).
- [x] **Python Sleep Debt Logic:** Verified debt calculation and T-0 exclusion with `test_debt_logic.py` (Passed).
- [x] **Android Logic Analysis:** Performed semantic analysis on Kotlin components to ensure type safety and logic flow.

### Manual Verification
- Verified that the Python widget UI now includes toggles for Bathyphase and Acrophase indicators in the Model Settings.
- Confirmed that "Sleep Need" values now align between Android and Python for the same main sleep history.

> [!NOTE]
> The energy curve may appear slightly "flatter" or more stable during the day due to the increase in $\tau_{wake}$ to 23.0h. This is expected and reflects modern performance modeling.
