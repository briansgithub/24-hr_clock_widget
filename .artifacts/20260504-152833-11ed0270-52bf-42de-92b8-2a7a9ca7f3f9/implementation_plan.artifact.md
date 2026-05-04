# Energy Logic and Model Tuning Improvements

Refine the energy level calculations to align with established chronobiology research (Two-Process and Three-Process models) and expose subjective parameters to the user for personalized tuning.

## User Review Required

> [!IMPORTANT]
> **Bathyphase to Alertness Peak Logic**: I am changing the logic that derives the circadian peak from the detected bathyphase (lowest HR during sleep). Research indicates that the primary alertness peak (Wake Maintenance Zone) occurs roughly 14-16 hours after the bathyphase (CBT min), rather than 5 hours. The 5-hour mark is typically a smaller morning peak, which is already handled by the model's 12-hour harmonic.

## Proposed Changes

### [Energy Logic](file:///H:/Desktop/widgets/24-hr_clock_widget/energy_logic.py)

#### [energy_logic.py](file:///H:/Desktop/widgets/24-hr_clock_widget/energy_logic.py)

- **Update `two_process_energy`**:
    - Accept new parameters: `tau_wake`, `tau_sleep`, `tau_inertia`, `debt_factor`.
    - Use standard default values: `tau_wake = 18.2`, `tau_sleep = 4.2`.
    - Improve sleep inertia calculation: `alertness = raw - (raw_at_wake * math.exp(-t / tau_inertia))` or similar to ensure it specifically suppresses morning energy.
- **Update `get_energy_level`**:
    - Change `peak_h = (bathyphase_hour + 5.0)` to `peak_h = (bathyphase_hour + 15.0)` to target the evening alertness peak.
    - Pass user-defined parameters through to `two_process_energy`.
- **Update `EnergyCurve` class**:
    - Add fields for all model parameters.
    - Update `_recompute_cache` and `_cached_args` to include these parameters so changes trigger a redraw.

---

### [UI and Controller](file:///H:/Desktop/widgets/24-hr_clock_widget/clock_widget.py)

#### [clock_widget.py](file:///H:/Desktop/widgets/24-hr_clock_widget/clock_widget.py)

- **New UI Controls**:
    - Add a new "MODEL" divider in the controls window.
    - Add sliders or entry fields for:
        - **Bedtime Goal**: (Currently hardcoded at 9.75).
        - **Circadian Phase (Peak Offset)**: Adjust the offset of the alertness peak from wake (default 10h).
        - **Homeostatic Tau (Awake)**: How fast you tire during the day (default 18.2).
        - **Homeostatic Tau (Sleep)**: How fast you recover during sleep (default 4.2).
        - **Sleep Inertia Duration**: How long it takes to "shake off" sleepiness after waking.
- **State Management**:
    - Define `tk.DoubleVar` or `tk.StringVar` for each new parameter.
    - Bind these variables to `draw_clock` to allow real-time visualization of parameter changes.
- **Persistence**:
    - Update `_load_sleep_settings` and `_save_sleep_settings` (or create a new `model_settings.json`) to persist these values across app restarts.

## Verification Plan

### Automated Tests
- No automated test suite exists, but I will perform manual value checks by logging the `raw` energy values at specific hours and comparing them to expected model outputs.

### Manual Verification
1.  **UI Interactivity**: Move the new sliders and verify the energy curve on the clock face updates instantly.
2.  **Debt Sensitivity**: Increase the debt factor and verify the energy curve shifts inward (lower energy).
3.  **Bathyphase Alignment**: If bathyphase is detected in the logs, verify the peak of the curve occurs in the late afternoon/evening (~15h after bathyphase).
4.  **Persistence**: Change a model parameter, restart the app, and verify the value is remembered.
