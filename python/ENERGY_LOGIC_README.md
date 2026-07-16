# Energy Logic — Calculation README

## Table of Contents

1. [Intuitive Summary](#intuitive-summary)
2. [The Two-Process Model](#the-two-process-model)
3. [Variable Reference](#variable-reference)
4. [Step-by-Step Calculation](#step-by-step-calculation)
5. [Supporting Functions](#supporting-functions)
6. [Visualization (EnergyCurve Class)](#visualization-energycurve-class)
7. [Pros & Cons](#pros--cons)
8. [Potential Improvements](#potential-improvements)
9. [Missing Variables & Oversights](#missing-variables--oversights)

---

## Intuitive Summary

> **In plain English:** your energy at any moment is the tug-of-war between two forces — a *biological clock* that pushes you to be alert during the day and sleepy at night (**Process C**), and a *sleep hunger* that builds the longer you stay awake and drains while you sleep (**Process S**). The model subtracts sleep hunger from your clock's alerting signal, then applies two penalties: one for the groggy first ~90 minutes after waking (**sleep inertia**), and another if you've been chronically under-sleeping (**sleep debt**). The result is a single 0–1 number: **0 = exhausted, 1 = peak alertness**.

The resulting energy curve across 24 hours looks roughly like this:

```
Energy
1.0 ┤          ╭──╮          ╭─╮
    │        ╭╯  ╰╮       ╭╯  ╰╮
0.7 ┤      ╭╯     ╰╮    ╭╯     ╰──╮
    │     ╭╯       ╰╮  ╭╯          ╰╮
0.3 ┤   ╭╯         ╰──╯             ╰──
    │  ╱  ← inertia   ↑ post-lunch      ╲ ← evening decline
0.0 ┤─╱              dip                  ╲─────
    └──┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───→ hours since wake
       0   2   4   6   8  10  12  14  16  18
```

**Key features the model captures:**
- **Slow start** — sleep inertia suppresses energy for ~1–2 hours after waking
- **Morning ramp** — energy climbs as the circadian signal strengthens
- **Late-morning peak** — highest alertness around 3–5 hours after waking
- **Post-lunch dip** — the well-known early-afternoon slump (~8 hours after wake)
- **Second wind** — a modest rebound in the late afternoon (~13 hours after wake)
- **Evening decline** — energy falls as sleep pressure accumulates and the circadian signal fades

---

## The Two-Process Model

This implementation is based on **Borbély's Two-Process Model of Sleep Regulation (1982)**, extended with elements from the **SAFTE** (Sleep, Activity, Fatigue, and Task Effectiveness) model. It combines:

| Process | What It Models | Biological Basis |
|---------|---------------|-----------------|
| **Process S** (Homeostatic) | Sleep pressure — rises while awake, falls while asleep | Adenosine accumulation in the basal forebrain |
| **Process C** (Circadian) | 24-hour alerting signal from the biological clock | Suprachiasmatic nucleus (SCN) of the hypothalamus |
| **Process W** (Sleep Inertia) | Post-wake grogginess that fades over ~90 min | Transitional cortical reactivation after sleep |
| **Debt Penalty** | Chronic sleep deprivation's drag on baseline alertness | Cumulative neurological fatigue |

**Final formula:**

```
alertness(t) = C(t) − S(t) − W(t) − debt_penalty
```

---

## Variable Reference

### Primary Function Parameters (`two_process_energy`)

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `t` | `float` | — | **Hours since wake-up** (0 = just woke up, 16 = typical bedtime). The independent variable that drives the whole curve. |
| `sleep_debt_hours` | `float` | `0.0` | **Accumulated sleep debt** over the last 14 nights, in hours. Computed as the weighted sum of `(sleep_need − actual_sleep)` per night. Higher values drag the entire curve downward. |
| `sleep_duration` | `float` | `7.5` | **Last night's total sleep**, in hours. Determines how much sleep pressure was discharged. Shorter sleep → higher residual pressure → lower energy. |
| `circadian_peak_offset` | `float` | `10.0` | **Hours after wake-up** when the circadian alerting signal peaks. Derived from the bathyphase (lowest core body temperature), or defaults to 10 hours (population average). |
| `clamp` | `bool` | `True` | Whether to clamp the output to `[0.0, 1.0]`. When `False`, raw values can exceed that range (used for normalization in the UI). |
| `tau_wake` | `float` | `23.0` | **Wake time constant** (hours). Controls how quickly sleep pressure builds. Updated to 2024 research standards (previously 18.2h). Larger = slower build-up. |
| `tau_sleep` | `float` | `4.0` | **Sleep time constant** (hours). Controls how quickly sleep pressure dissipates during sleep. Updated to 2024 research standards (previously 4.2h). |
| `tau_inertia` | `float` | `1.5` | **Sleep inertia time constant** (hours). Controls how quickly post-wake grogginess fades. After `1.5 × tau_inertia ≈ 2.25h`, ~78% of inertia is gone. |
| `debt_factor` | `float` | `1.0` | **Sensitivity multiplier** for the debt penalty. Values > 1.0 make the model more punishing toward sleep debt; < 1.0 make it more forgiving. |

### Internal Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `S_max` | `1.0` | Maximum possible sleep pressure (asymptote after infinite wakefulness) |
| `S_min` | `0.05` | Minimum sleep pressure floor (even perfect sleep leaves a trace) |
| `C_primary amplitude` | `0.55` | Strength of the main 24-hour circadian cosine wave |
| `C_secondary amplitude` | `-0.15` | Strength of the 12-hour harmonic (creates the post-lunch dip and second wind) |
| `C_t baseline offset` | `1.10` | Vertical shift to keep early-morning alertness positive |
| `debt cap` | `0.35` | Maximum debt penalty (at 25 hours of accumulated debt) |
| `debt_saturation` | `25.0` | Hours of debt at which the penalty is fully saturated |
| `decay_factor` (in `compute_sleep_debt`) | `0.9` | Exponential decay weight — each night further back is 90% as impactful |

### Wrapper Function Parameters (`get_energy_level`)

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `h_clock` | `float` | — | **Wall-clock hour** (0.0–24.0). The function converts this to hours-since-wake internally via `(h_clock − wake_hour) % 24`. |
| `wake_hour` | `float` | — | **Hour of the day the user woke up** (e.g., 7.0 = 7:00 AM). Sourced from Fitbit sleep data. |
| `bathyphase_hour` | `float` | `None` | **Clock hour of the lowest heart rate** during sleep (proxy for core body temperature nadir). Used to calculate `circadian_peak_offset` as `(bathyphase + 15) − wake_hour`. |

---

## Step-by-Step Calculation

### Step 1 — Process S: Homeostatic Sleep Pressure

Sleep pressure is modeled as an exponential charge/discharge cycle:

```
During wakefulness (t < sleep_start):
    S(t) = S₀ + (S_max − S₀) × (1 − e^(−t/τ_wake))

During sleep (t ≥ sleep_start):
    S(t) = S_min + (S_bed − S_min) × e^(−t_sleep/τ_sleep)
```

Where:
- **`sleep_start`** = `24.0 − sleep_duration` (hours into the wake period when sleep begins)
- **`S₀`** = sleep pressure at the moment of waking (derived from steady-state boundary conditions so the 24h cycle is continuous)
- **`S_bed`** = sleep pressure at bedtime (the peak before sleep begins)
- **`E_w`** = `e^(−sleep_start/τ_wake)` — how much of the wake exponential remains
- **`E_s`** = `e^(−sleep_duration/τ_sleep)` — how much sleep pressure remains after sleeping

The steady-state formula ensures that `S₀` at the start of one day equals `S₀` at the start of the next, creating a self-consistent cycle.

### Step 2 — Process C: Circadian Alerting Signal

The circadian signal is a sum of two cosine waves:

```
C_primary(t)   =  0.55 × cos(π/12 × (t − peak_offset))     ← 24h period
C_secondary(t) = −0.15 × cos(π/6  × (t − 8.0))             ← 12h period
C(t)           =  1.10 + C_primary + C_secondary
```

- The **primary** wave peaks at `t = peak_offset` (default 10h after wake) and troughs 12 hours later
- The **secondary** harmonic troughs at `t = 8h` and crests at `t = 14h`, producing:
  - A **post-lunch dip** around 6–8 hours post-wake
  - A **second wind** around 13–14 hours post-wake

### Step 3 — Raw Alertness

```
raw(t) = C(t) − S(t)
```

This is the core of the model: alertness = circadian drive minus sleep pressure.

### Step 4 — Sleep Inertia (Process W)

```
W(t) = max(0, raw(0)) × e^(−t / τ_inertia)
```

- Computes what `raw` alertness *would be* at `t = 0` (wake time)
- Subtracts a decaying penalty so that alertness starts near zero and ramps up over ~90 minutes
- The `max(0, ...)` guard prevents inertia from *adding* energy if `raw(0)` is already negative

### Step 5 — Debt Penalty

```
debt_penalty = min(0.35, (sleep_debt_hours × debt_factor) / 25.0)
```

- Linear scaling of accumulated debt, capped at 0.35
- At 25 hours of weighted debt (~1.8h deficit × 14 nights), the penalty saturates
- This is a **flat offset** — it shifts the entire curve down uniformly

### Step 6 — Final Alertness

```
alertness(t) = raw(t) − W(t) − debt_penalty
clamped:       max(0.0, min(1.0, alertness))
```

---

## Supporting Functions

### `compute_sleep_debt(sleep_logs, sleep_need_hours, include_naps, excluded_dates)`

Calculates a **weighted 14-night sleep debt** from Fitbit sleep logs.

| Feature | Detail |
|---------|--------|
| **Window** | 15 days (T-0 through T-14) |
| **Weighting** | Exponential decay: `weight = 0.9^i` where `i` = days ago. Last night has weight 1.0; 14 nights ago has weight `0.9^14 ≈ 0.23` |
| **Efficiency** | Calculated as the **Average of Ratios** (mean of individual nightly efficiencies) for main sleep periods. |
| **Naps** | Included in debt reduction at 100% efficiency, but **excluded** from the average sleep efficiency metric. |
| **Missing data** | Dates without logs count as **0 hours of sleep** (worst case) |
| **Excluded dates** | Completely skipped (useful for today's incomplete data) |

### `find_bathyphase(intraday_hr)`

Identifies the **bathyphase** — the clock hour with the lowest average heart rate during sleep.

- **Parabolic Fit**: Uses a **Parabolic Vertex Fit** algorithm. Instead of just picking the lowest 1-hour bucket, it analyzes the bucket and its two neighbors to calculate the mathematical nadir with sub-hour precision (e.g., 04:22 AM).
- This is a proxy for the **core body temperature nadir**, which anchors the circadian clock.
- Requires Fitbit "Personal" app type for intraday access.

---

## Visualization (EnergyCurve Class)

The `EnergyCurve` class renders the model onto a 24-hour clock face:

| Feature | Implementation |
|---------|---------------|
| **Caching** | Precomputes 1440 energy samples (one per minute) and invalidates only when parameters change |
| **Color gradient** | Interpolates from cyan `(0, 210, 255)` at low energy to amber `(255, 75, 43)` at high energy |
| **Radius mapping** | Energy level controls the radial distance from the clock center. In normalized mode, the range is scaled to 10%–90% of the clock radius. |
| **Visual Indicators** | Displays inward-pointing triangles for **Bathyphase** (Trough) and **Acrophase** (Peak). |
| **Acrophase Logic** | Scans the personalized energy curve (144 samples) to mark the actual highest point of alertness today. |
| **Drawing resolution** | 72 line segments (one every 20 minutes) for smooth visual appearance. |

---

## Pros & Cons

### ✅ Pros

| # | Advantage |
|---|-----------|
| 1 | **Scientifically grounded** — Based on Borbély's well-validated two-process model, the gold standard in sleep research for 40+ years |
| 2 | **Personalized circadian timing** — Uses individual bathyphase (HR nadir) rather than a population average, capturing chronotype differences (early birds vs. night owls) |
| 3 | **Captures real phenomena** — Post-lunch dip, sleep inertia, and second wind all emerge naturally from the math rather than being hardcoded |
| 4 | **Tunable time constants** — All `tau` values are exposed parameters, allowing per-user calibration without modifying the model structure |
| 5 | **Weighted sleep debt** — Exponential decay weighting of the 14-day window correctly emphasizes recent nights over ancient history |
| 6 | **Efficient rendering** — Minute-level caching with parameter-based invalidation keeps UI updates cheap |
| 7 | **Continuous boundary conditions** — The steady-state `S₀` calculation ensures the 24h cycle is seamless, avoiding artificial discontinuities |

### ❌ Cons

| # | Disadvantage |
|---|-------------|
| 1 | **No real-time feedback** — The curve is purely predictive based on sleep data; it doesn't adjust if, say, your heart rate shows you're more alert than expected |
| 2 | **Single-sleep-episode model** — Assumes one consolidated sleep period per day. Polyphasic sleepers, shift workers, or nap-takers will get inaccurate curves |
| 3 | **Flat debt penalty** — Sleep debt shifts the curve uniformly downward, but in reality debt disproportionately impairs executive function and late-day alertness |
| 4 | **Bathyphase resolution** — Using hourly HR buckets (integer hour) introduces up to ±30 minutes of error in circadian phase estimation |
| 5 | **No individual amplitude calibration** — The circadian and homeostatic amplitudes (0.55, 0.15) are hardcoded population averages; individual variation can be ±30% |
| 6 | **Missing sleep stage weighting** — A night with 7.5h of sleep is treated identically regardless of whether it contained 1h or 2.5h of deep sleep |
| 7 | **Static model** — The curve is computed once from last night's data and doesn't evolve intra-day (e.g., a mid-day nap won't reset the curve) |

---

## Potential Improvements

| # | Improvement | Description | Confidence |
|---|-------------|-------------|:----------:|
| 1 | **Real-time HR feedback loop** | Blend the predicted curve with live heart rate variability (HRV) as a reality check. When current HRV diverges from the prediction, smoothly adjust the curve. Fitbit already provides intraday HR. | **82%** |
| 2 | **Sleep stage weighting** | Weight `sleep_duration` by sleep stage quality: deep sleep could count as 1.3× and REM as 1.1×, while light sleep counts as 0.8×. Fitbit provides stage breakdowns. | **78%** |
| 3 | **Nap integration** | When a nap is detected (via Fitbit), partially reset Process S by applying `tau_sleep` for the nap duration, then recompute the remainder of the curve. | **75%** |
| 4 | **Non-uniform debt penalty** | Make the debt penalty a function of `t` rather than a constant: `debt_penalty(t) = base_penalty × (1 + 0.3 × S(t))`. This would make debt effects worse when sleep pressure is already high (late evening). | **70%** |
| 5 | **Bathyphase interpolation** | Instead of picking the single lowest-HR hour, fit a parabola to the 3 lowest-HR hours to get sub-hour precision on the circadian nadir. | **65%** |
| 6 | **Adaptive time constants** | Track prediction accuracy over weeks (e.g., user-reported energy vs. predicted). Use gradient descent to adjust `tau_wake`, `tau_sleep`, and the circadian amplitudes for the individual. | **60%** |
| 7 | **Multi-day carry-forward** | Instead of recomputing `S₀` from steady-state assumptions each day, carry `S` forward from the previous day's actual end-of-sleep value. This would naturally handle irregular sleep schedules. | **72%** |
| 8 | **Light exposure integration** | Bright light suppresses melatonin and shifts the circadian phase. Integrating phone light sensor data or sunrise/sunset times could modulate `circadian_peak_offset` throughout the day. | **55%** |

---

## Missing Variables & Oversights

The following are well-established factors that affect human energy levels but are **not considered** in this model:

> [!IMPORTANT]
> These omissions don't necessarily mean the model is broken — they represent potential blind spots that could reduce prediction accuracy for certain users or situations.

### High-Impact Omissions

| Factor | Why It Matters | Data Source Feasibility |
|--------|---------------|----------------------|
| **Caffeine** | Caffeine blocks adenosine receptors, directly counteracting Process S. A cup of coffee at 2 PM can delay the evening decline by 1–2 hours. Half-life is ~5–6 hours. | ❌ No passive sensor — would require manual logging |
| **Exercise timing** | Morning exercise phase-advances the circadian clock; evening exercise can delay it. Acute exercise also provides a 1–2 hour alertness boost (cortisol + endorphins). The model has `exertion_factor` in the header comments but it is **not implemented**. | ✅ Fitbit activity data is available |
| **Meal timing & composition** | Large meals (especially high-carb) trigger postprandial somnolence that amplifies the post-lunch dip. Fasting can maintain alertness. | ❌ No passive sensor |
| **Sleep quality (stages)** | The model treats all sleep minutes equally. In reality, deep sleep (N3) is disproportionately restorative for Process S discharge, while REM is critical for cognitive function. | ✅ Fitbit sleep stages are available |

### Medium-Impact Omissions

| Factor | Why It Matters | Data Source Feasibility |
|--------|---------------|----------------------|
| **Light exposure** | Bright light (especially blue light) is the primary zeitgeber for the SCN. Indoor-only days can flatten the circadian amplitude by 20–40%. | ⚠️ Phone light sensor is coarse |
| **Stress / cortisol** | Acute stress elevates cortisol, temporarily boosting alertness but accelerating long-term fatigue. Chronic stress flattens the cortisol curve and impairs sleep quality. | ⚠️ Could approximate via HRV trends |
| **Alcohol** | Even moderate alcohol disrupts REM sleep in the second half of the night, reducing sleep quality without changing total sleep duration. | ❌ Manual logging required |
| **Hydration** | Mild dehydration (1–2% body weight) impairs cognitive performance and subjective energy by ~10–15%. | ❌ No passive sensor |
| **Ambient temperature** | Thermoneutral zone violations (too hot/cold) reduce sleep efficiency and increase daytime fatigue. | ⚠️ Weather API could approximate |

### Low-Impact but Notable

| Factor | Why It Matters |
|--------|---------------|
| **Illness / immune activation** | Infection triggers cytokines (IL-1, TNF-α) that directly cause sleepiness. The model has no concept of sickness. |
| **Menstrual cycle** | Progesterone in the luteal phase increases core body temperature by ~0.3°C and shifts the circadian phase, affecting energy in roughly half the population. |
| **Age** | Circadian amplitude decreases with age; `tau_wake` and sleep architecture change significantly. The model's constants are implicitly calibrated for a "typical adult." |
| **Medications** | Antihistamines, beta-blockers, SSRIs, and many other common medications have significant effects on alertness and sleep architecture. |

---

> [!NOTE]
> The most impactful and *feasible* improvement would be **sleep stage weighting** (#2 in improvements) combined with the already-commented-but-unimplemented **`exertion_factor`**. Both use data the Fitbit API already provides, require no manual logging, and address the two largest omissions in the current model.
