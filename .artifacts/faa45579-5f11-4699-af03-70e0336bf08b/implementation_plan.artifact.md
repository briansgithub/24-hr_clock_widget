# Unified Sleep & Energy Logic (2024 Research Update)

This plan outlines the unification of the Android and Python energy models to use the latest 2024 sleep research constants, precise bathyphase detection, and consistent efficiency calculations.

## User Review Required

> [!IMPORTANT]
> **Breaking Change in Defaults:** Updating $\tau_{wake}$ from 18.2 to 23.0 will make the energy curve appear "flatter" and more resilient during the day, which better reflects modern performance research.
>
> **Efficiency Calculation Change:** Transitioning to "Average of Ratios" (excluding naps) will stabilize the `Sleep Need` metric.

## Proposed Changes

### [Android App]

#### [MODIFY] [EnergyCalculator.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/EnergyCalculator.kt)
- **Research-Based Defaults:** Update `tauWake` to **23.0** and `tauSleep` to **4.0**.
- **Precise Bathyphase:** Refactor `findBathyphase` from a simple "min hour" search to a **Parabolic Vertex Fit**. It will now take the lowest HR hour and its two neighbors to find the true mathematical nadir (returning a floating-point hour).
- **Acrophase Logic:** Standardize fallback `peakOffset` in `getEnergyLevel` to **13.0** (standard 15h offset from nadir, assuming nadir is 2h before wake).

#### [MODIFY] [FitbitManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/FitbitManager.kt)
- **Efficiency Unification:** Refactor to use **Average of Ratios** (excluding naps).
    - `efficiency = mainSleepLogs.map { log -> log.minutesAsleep / log.timeInBed }.average()`
    - Naps are explicitly ignored in this calculation but passed to debt logic.

#### [MODIFY] [ClockRenderer.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/wallpaper/ClockRenderer.kt)
- **Acrophase Alignment:** Ensure the visual Acrophase indicator and the `Energy %` reference use the same scanned peak from the personalized curve.

---

### [Python Widget]

#### [MODIFY] [energy_logic.py](file:///H:/Desktop/widgets/24-hr_clock_widget/python/energy_logic.py)
- **Unify Constants:** Match Android defaults (`tau_wake=23.0`, `tau_sleep=4.0`).
- **Precise Bathyphase:** Implement the same **Parabolic Vertex Fit** as Android.
- **Efficiency Logic:** Align `get_all_energy_inputs` to use **Average of Ratios** for `mainSleep` only.

#### [MODIFY] [clock_widget.py](file:///H:/Desktop/widgets/24-hr_clock_widget/python/clock_widget.py)
- **New Visual Indicators:** Implement `draw_bathyphase_indicator` and `draw_acrophase_indicator` methods in the `EnergyCurve` class to match the Android "inward-pointing triangle" style.
- **Acrophase Definition:** Ensure the Acrophase indicator marks the peak of the *combined* energy curve (the highest point of the final model output).
- **Matching Defaults:** Update UI variables for constants to match 2024 research.

---

## Verification Plan

### Automated Tests
- **Android:** Run `EnergyCalculatorTest.kt` with updated constants.
- **Python:** Run `test_debt_logic.py`. Create a new `test_bathyphase.py` to verify the parabolic fit accuracy.

### Manual Verification
- **Visual Sync:** Place Android and Python side-by-side. Confirm the Bathyphase (inward triangle) and Acrophase (peak indicator) are in identical clock positions for the same input data.
- **Numerical Sync:** Verify "Sleep Need" and "Efficiency %" labels match exactly.
- **Nap Behavior:** Add a nap via Fitbit (or mock data). Verify Sleep Debt decreases, but Efficiency % remains unchanged.
