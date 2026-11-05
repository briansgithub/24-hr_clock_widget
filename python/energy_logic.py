import math
import json
import os
import tkinter as tk
import copy
from dataclasses import dataclass
from enum import Enum


class SleepIntervalKind(str, Enum):
    MAIN = "main"
    NAP = "nap"


class EnergyPredictionState(str, Enum):
    AWAKE = "awake"
    ASLEEP = "asleep"
    UNAVAILABLE = "unavailable"


@dataclass(frozen=True)
class ClockSleepInterval:
    """A half-open sleep interval on a circular 24-hour clock."""

    start_hour: float
    end_hour: float
    kind: SleepIntervalKind

    def contains(self, clock_hour: float) -> bool:
        start = self.start_hour % 24.0
        end = self.end_hour % 24.0
        hour = clock_hour % 24.0
        if math.isclose(start, end, abs_tol=1e-9):
            return False
        if start < end:
            return start <= hour < end
        return hour >= start or hour < end


@dataclass(frozen=True)
class SleepModelContext:
    main_sleep_duration: float | None
    all_sleep_intervals: tuple[ClockSleepInterval, ...] = ()
    modeled_nap_intervals: tuple[tuple[float, float], ...] = ()

    @property
    def available(self) -> bool:
        return self.main_sleep_duration is not None and any(
            interval.kind == SleepIntervalKind.MAIN
            for interval in self.all_sleep_intervals
        )


@dataclass(frozen=True)
class EnergyPrediction:
    state: EnergyPredictionState
    value: float | None = None

    def __post_init__(self) -> None:
        if (self.state == EnergyPredictionState.AWAKE) != (self.value is not None):
            raise ValueError("Only awake energy predictions may contain a value")


def awake_clock_segments(
    intervals: tuple[ClockSleepInterval, ...] | list[ClockSleepInterval],
) -> tuple[tuple[float, float], ...]:
    """Return non-overlapping awake clock segments, joining across midnight."""
    spans: list[tuple[float, float]] = []
    for interval in intervals:
        start = interval.start_hour % 24.0
        end = interval.end_hour % 24.0
        if math.isclose(start, end, abs_tol=1e-9):
            continue
        if start < end:
            spans.append((start, end))
        else:
            spans.extend(((start, 24.0), (0.0, end)))

    if not spans:
        return ((0.0, 24.0),)

    spans.sort()
    merged: list[tuple[float, float]] = []
    for start, end in spans:
        if merged and start <= merged[-1][1] + 1e-9:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))

    gaps: list[tuple[float, float]] = []
    cursor = 0.0
    for start, end in merged:
        if start > cursor + 1e-9:
            gaps.append((cursor, start))
        cursor = max(cursor, end)
    if cursor < 24.0 - 1e-9:
        gaps.append((cursor, 24.0))

    if len(gaps) > 1 and gaps[0][0] <= 1e-9 and gaps[-1][1] >= 24.0 - 1e-9:
        wrapped = (gaps[-1][0], gaps[0][1] + 24.0)
        gaps = gaps[1:-1] + [wrapped]
    return tuple(gaps)

# -----------------------------------------------------------------------------
# FITBIT DATA INPUTS (populate these from the API before drawing)
# -----------------------------------------------------------------------------
#
# Required Fitbit API scopes: sleep | heartrate | activity (optional)
#
# Variable              Fitbit endpoint
# -----------------------------------------------------------------------------
# wake_hour           : /1.2/user/-/sleep/date/{date}.json
#                       -> summary.mainSleep.endTime (parse hour + minute)
#
# sleep_duration_hrs  : same endpoint
#                       -> summary.mainSleep.minutesAsleep / 60
#
# sleep_debt_hours    : /1.2/user/-/sleep/date/{start}/{end}.json (14 days)
#                       -> sum(sleep_need - minutesAsleep/60) for each day
#                       sleep_need = your personal baseline (e.g. 8.0 h)
#
# bathyphase_hour     : /1/user/-/activities/heart/date/{date}/1d/1min.json
#   (Intraday HR)      -> find the clock-hour of lowest avg HR during sleep
#   Requires "Personal" This is the hour of lowest core body temperature.
#   app type on         Circadian peak = bathyphase + 4-6 h
#   dev.fitbit.com      If unavailable, fall back to wake_hour + 10
#
# exertion_factor     : /1/user/-/activities/date/{yesterday}.json (optional)
#   (optional)          -> summary.veryActiveMinutes / 60
#                       Increases effective sleep need when > 1h hard effort
# -----------------------------------------------------------------------------

def two_process_energy(
    t: float,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    circadian_peak_offset: float = 10.0,
    clamp: bool = True,
    tau_wake: float = 23.0,
    tau_sleep: float = 4.0,
    tau_inertia: float = 1.5,
    debt_factor: float = 1.0,
) -> float:
    """
    Two-process model of alertness (Borbely 1982, SAFTE).

    Args:
        t                    : Hours since wake-up (0-24).
        sleep_debt_hours     : Accumulated debt over last 14 nights (hours).
        sleep_duration       : Last night's total sleep (hours).
        circadian_peak_offset: Hours after wake when circadian alertness peaks.
                               Derive from bathyphase: peak = bathyphase + 15h,
                               expressed as offset from wake_hour.
                               Falls back to 10.0 if intraday HR unavailable.
        tau_wake             : Time constant for build-up of sleep pressure (h).
        tau_sleep            : Time constant for dissipation of sleep pressure (h).
        tau_inertia          : Time constant for clearing sleep inertia (h).
        debt_factor          : Sensitivity multiplier for sleep debt penalty.

    Returns:
        Alertness 0.0-1.0.
    """

    # -- 1. PROCESS S - Homeostatic Sleep Pressure ----------------------------
    # Pressure builds exponentially while awake (tau ~ 18 h to saturate).
    # After sleep it is partially discharged; how much depends on sleep length
    # (tau_sleep ~ 4 h - a 4 h nap halves residual pressure).
    
    S_max = 1.0
    S_min = 0.05

    # Steady-state boundary conditions for continuous 24h loop
    sleep_start = 24.0 - sleep_duration
    E_w = math.exp(-sleep_start / tau_wake)
    E_s = math.exp(-sleep_duration / tau_sleep)
    
    # Avoid division by zero in pathological cases
    divisor = 1.0 - E_w * E_s
    if divisor < 0.0001:
        divisor = 0.0001
        
    S0 = (S_max * E_s * (1.0 - E_w) + S_min * (1.0 - E_s)) / divisor
    S_bed = S0 * E_w + S_max * (1.0 - E_w)

    # Pressure at time t
    if t < sleep_start:
        # Awake: pressure builds
        S_t = S0 + (S_max - S0) * (1.0 - math.exp(-t / tau_wake))
    else:
        # Asleep: pressure discharges
        t_sleep = t - sleep_start
        S_t = S_min + (S_bed - S_min) * math.exp(-t_sleep / tau_sleep)

    # -- 2. PROCESS C - Circadian Alerting Signal -----------------------------
    # Primary 24 h cosine peaks at circadian_peak_offset hours after wake.
    # Secondary 12 h harmonic adds:
    #   * a post-lunch dip (~8 h after wake)
    #   * an evening second wind (~13 h after wake)
    
    C_primary   = 0.55 * math.cos(
        (math.pi / 12) * (t - circadian_peak_offset)
    )
    C_secondary = -0.15 * math.cos(
        (math.pi / 6)  * (t - 8.0)          # trough near 8 h, crest near 14 h
    )
    C_t = 1.10 + C_primary + C_secondary    # shifted so morning energy is positive

    # -- 3. RAW ALERTNESS = C(t) - S(t) ---------------------------------------
    raw = C_t - S_t

    # -- 4. SLEEP INERTIA (first ~90 min) -------------------------------------
    # Process W: Alertness is suppressed immediately after waking.
    # W(t) = (raw_at_wake) * exp(-t / tau_inertia)
    # We calculate raw_at_wake (t=0) to ensure alertness starts at 0.0.
    C_at_wake = 1.10 + 0.55 * math.cos((math.pi / 12) * (-circadian_peak_offset)) + \
                -0.15 * math.cos((math.pi / 6) * (-8.0))
    raw_at_wake = C_at_wake - S0
    inertia_penalty = max(0.0, raw_at_wake) * math.exp(-t / tau_inertia)
    
    # -- 5. DEBT PENALTY (shifts floor down) ----------------------------------
    # Weighted debt of 25 hours (e.g. 1.8h deficit for 14 days) results in max penalty.
    debt_penalty = min(0.35, (sleep_debt_hours * debt_factor) / 25.0)
    
    alertness = raw - inertia_penalty - debt_penalty
    
    if clamp:
        return max(0.0, min(1.0, alertness))
    return alertness


# -----------------------------------------------------------------------------
# PERSONAL EMPIRICAL MODELS
# Loaded from python/personal_energy_fit.json after fitting.
# -----------------------------------------------------------------------------

_personal_fit_cache: dict | None = None

ADAPTIVE_MODEL_ID = "M8_adaptive_sleep"
AWAKE_HYBRID_MODEL_ID = "M9_awake_hybrid"
ADAPTIVE_MODEL_IDS = {ADAPTIVE_MODEL_ID, AWAKE_HYBRID_MODEL_ID}
ADAPTIVE_MODEL_DEFAULTS = {
    "circadian_coefs": [
        51.79829899798624,
        -8.970119863421589,
        -19.40441654693655,
        4.839958739091168,
        -5.463160275994447,
    ],
    "reference_sleep_hours": 8.0,
    "reference_peak_clock": 13.48,
    "max_phase_shift_hours": 3.0,
    "tau_wake": 23.0,
    "tau_sleep": 4.0,
    "inertia_tau": 0.75,
    "homeostatic_scale": 25.0,
    "debt_scale": 2.0,
    "debt_saturation_hours": 12.0,
    "inertia_scale": 12.0,
    "process_s_min": 0.05,
    "process_s_max": 1.0,
    "homeostatic_delta_min": -0.15,
    "homeostatic_delta_max": 0.35,
}


def _committed_m8_fallback(reason: str) -> dict:
    return {
        "schema_version": 1,
        "model_id": ADAPTIVE_MODEL_ID,
        "params": copy.deepcopy(ADAPTIVE_MODEL_DEFAULTS),
        "fallback_reason": reason,
    }


def _valid_adaptive_fit(data: dict) -> bool:
    if not isinstance(data, dict):
        return False
    model_id = data.get("model_id")
    if model_id not in ADAPTIVE_MODEL_IDS:
        return True
    if model_id == AWAKE_HYBRID_MODEL_ID and data.get("schema_version") != 2:
        return False
    params = data.get("params")
    if not isinstance(params, dict):
        return False
    required = set(ADAPTIVE_MODEL_DEFAULTS)
    if not required.issubset(params):
        return False
    coefficients = params.get("circadian_coefs")
    try:
        harmonics = int(params.get("harmonics", 2))
    except (TypeError, ValueError):
        return False
    if harmonics not in (2, 3) or not isinstance(coefficients, list):
        return False
    if len(coefficients) != 1 + 2 * harmonics:
        return False
    numeric = [value for key, value in params.items() if key != "circadian_coefs"]
    numeric.extend(coefficients)
    if not all(
        isinstance(value, (int, float)) and math.isfinite(value)
        for value in numeric
    ):
        return False
    return (
        float(params["tau_wake"]) == 23.0
        and float(params["tau_sleep"]) == 4.0
        and float(params["reference_sleep_hours"]) == 8.0
        and float(params["debt_saturation_hours"]) == 12.0
        and float(params["max_phase_shift_hours"]) == 3.0
    )


def load_personal_fit(path: str | None = None) -> dict | None:
    """Load personal model config; returns None if missing or disabled."""
    global _personal_fit_cache
    if path is None:
        path = os.path.join(os.path.dirname(__file__), "personal_energy_fit.json")
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        settings_path = os.path.join(os.path.dirname(__file__), "sleep_settings.json")
        if os.path.exists(settings_path):
            with open(settings_path, "r", encoding="utf-8") as f:
                settings = json.load(f)
            pm = settings.get("personal_model", {})
            if not pm.get("enabled", True):
                return None
        if not _valid_adaptive_fit(data):
            data = _committed_m8_fallback("invalid adaptive model configuration")
        _personal_fit_cache = data
        return data
    except (json.JSONDecodeError, OSError):
        fallback = _committed_m8_fallback("unreadable adaptive model configuration")
        _personal_fit_cache = fallback
        return fallback


def personal_m4_energy(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    sleep_efficiency: float = 0.9,
    params: dict | None = None,
    clamp: bool = True,
) -> float:
    """
    M4: baseline(day) + circadian shape. Returns alertness 0.0–1.0.
    """
    if params is None:
        fit = load_personal_fit()
        params = (fit or {}).get("params", {})

    baseline = (
        params.get("baseline_intercept", 50.0)
        + params.get("baseline_sleep_coef", 0.0) * sleep_duration
        + params.get("baseline_debt_coef", 0.0) * sleep_debt_hours
        + params.get("baseline_efficiency_coef", 0.0) * sleep_efficiency
    )
    circ_amp = params.get("circ_amp", 15.0)
    circ_amp2 = params.get("circ_amp2", 5.0)
    circ_offset = params.get("circ_offset", 8.0)
    shape = (
        circ_amp * math.cos((math.pi / 12.0) * (t - circ_offset))
        + circ_amp2 * math.cos((math.pi / 6.0) * (t - 8.0))
    )
    energy_pct = max(0.0, min(100.0, baseline + shape))
    alertness = energy_pct / 100.0
    if clamp:
        return max(0.0, min(1.0, alertness))
    return alertness


def personal_clock_fourier_energy(
    clock_hour: float,
    params: dict | None = None,
    clamp: bool = True,
) -> float | None:
    """Data-fitted two-harmonic energy curve on local clock time.

    The fitted coefficients are on the 0–100 scale and are intentionally
    independent of wake-time estimates: the empirical log contains large
    wake-time variation, while the observed ratings align more reliably to
    the local clock hour.  Returns alertness on the 0.0–1.0 scale.
    """
    params = params or {}
    coefs = params.get("fourier_coefs", [])
    if len(coefs) < 5:
        return None

    omega = 2.0 * math.pi / 24.0
    theta = omega * (clock_hour % 24.0)
    energy_pct = (
        float(coefs[0])
        + float(coefs[1]) * math.cos(theta)
        + float(coefs[2]) * math.sin(theta)
        + float(coefs[3]) * math.cos(2.0 * theta)
        + float(coefs[4]) * math.sin(2.0 * theta)
    )
    energy_pct = max(0.0, min(100.0, energy_pct))
    alertness = energy_pct / 100.0
    if clamp:
        return max(0.0, min(1.0, alertness))
    return alertness


def _adaptive_params(params: dict | None) -> dict:
    merged = dict(ADAPTIVE_MODEL_DEFAULTS)
    if params:
        merged.update(params)
    return merged


def _process_s_after_sleep(
    sleep_duration: float,
    tau_wake: float,
    tau_sleep: float,
    s_min: float,
    s_max: float,
) -> float:
    """Return Process S at wake after a repeating wake/sleep cycle."""
    duration = max(0.0, min(24.0, float(sleep_duration)))
    tau_wake = max(0.001, float(tau_wake))
    tau_sleep = max(0.001, float(tau_sleep))
    awake_duration = 24.0 - duration
    e_wake = math.exp(-awake_duration / tau_wake)
    e_sleep = math.exp(-duration / tau_sleep)
    divisor = max(0.0001, 1.0 - e_wake * e_sleep)
    state = (
        s_max * e_sleep * (1.0 - e_wake)
        + s_min * (1.0 - e_sleep)
    ) / divisor
    return max(s_min, min(s_max, state))


def _process_s_awake(state: float, hours: float, tau_wake: float, s_max: float) -> float:
    duration = max(0.0, hours)
    return state + (s_max - state) * (1.0 - math.exp(-duration / max(0.001, tau_wake)))


def _process_s_asleep(state: float, hours: float, tau_sleep: float, s_min: float) -> float:
    duration = max(0.0, hours)
    return s_min + (state - s_min) * math.exp(-duration / max(0.001, tau_sleep))


def _normalise_nap_intervals(
    nap_intervals: list[tuple[float, float]] | tuple[tuple[float, float], ...] | None,
) -> tuple[tuple[float, float], ...]:
    """Clip, sort, and merge nap intervals expressed as hours after main wake."""
    ordered: list[tuple[float, float]] = []
    for interval in nap_intervals or ():
        try:
            start, end = float(interval[0]), float(interval[1])
        except (IndexError, TypeError, ValueError):
            continue
        start = max(0.0, min(24.0, start))
        end = max(0.0, min(24.0, end))
        if end > start:
            ordered.append((start, end))
    ordered.sort()

    merged: list[tuple[float, float]] = []
    for start, end in ordered:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return tuple(merged)


def _adaptive_process_s_context(
    hours_since_main_wake: float,
    sleep_duration: float,
    nap_intervals: list[tuple[float, float]] | tuple[tuple[float, float], ...] | None,
    params: dict,
) -> tuple[float, float, bool]:
    """Return (actual-minus-rested Process S, hours since last wake, asleep)."""
    tau_wake = float(params["tau_wake"])
    tau_sleep = float(params["tau_sleep"])
    s_min = float(params["process_s_min"])
    s_max = float(params["process_s_max"])
    reference_sleep = float(params["reference_sleep_hours"])

    actual = _process_s_after_sleep(sleep_duration, tau_wake, tau_sleep, s_min, s_max)
    rested = _process_s_after_sleep(reference_sleep, tau_wake, tau_sleep, s_min, s_max)
    target = max(0.0, min(24.0, hours_since_main_wake))
    cursor = 0.0
    last_wake = 0.0

    for start, end in _normalise_nap_intervals(nap_intervals):
        if start >= target:
            break
        awake_hours = max(0.0, start - cursor)
        actual = _process_s_awake(actual, awake_hours, tau_wake, s_max)
        rested = _process_s_awake(rested, awake_hours, tau_wake, s_max)

        sleep_end = min(target, end)
        actual = _process_s_asleep(actual, sleep_end - start, tau_sleep, s_min)
        rested = _process_s_asleep(rested, sleep_end - start, tau_sleep, s_min)
        if target < end:
            return actual - rested, 0.0, True
        cursor = end
        last_wake = end

    awake_hours = max(0.0, target - cursor)
    actual = _process_s_awake(actual, awake_hours, tau_wake, s_max)
    rested = _process_s_awake(rested, awake_hours, tau_wake, s_max)
    return actual - rested, max(0.0, target - last_wake), False


def _circular_hour_difference(target: float, reference: float) -> float:
    return ((target - reference + 12.0) % 24.0) - 12.0


def adaptive_sleep_energy(
    clock_hour: float,
    wake_hour: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    bathyphase_hour: float | None = None,
    circadian_peak_offset: float | None = None,
    nap_intervals: list[tuple[float, float]] | tuple[tuple[float, float], ...] | None = None,
    params: dict | None = None,
    clamp: bool = True,
) -> float:
    """M8: empirical circadian baseline with sleep-driven state corrections.

    The baseline is a personal two-harmonic local-clock fit. Process S changes
    continuously from the main wake through any naps, while sleep debt and
    sleep inertia can only reduce predicted alertness. Fitbit minutesAsleep is
    already an effective-sleep measure, so efficiency is deliberately not
    applied a second time.
    """
    model = _adaptive_params(params)
    t = (clock_hour - wake_hour) % 24.0
    process_delta, hours_since_last_wake, _ = _adaptive_process_s_context(
        t, sleep_duration, nap_intervals, model
    )

    reference_peak = float(model["reference_peak_clock"])
    desired_peak = None
    if bathyphase_hour is not None:
        desired_peak = (float(bathyphase_hour) + 15.0) % 24.0
    elif circadian_peak_offset is not None:
        desired_peak = (float(wake_hour) + float(circadian_peak_offset)) % 24.0

    phase_shift = 0.0
    if desired_peak is not None:
        max_shift = max(0.0, float(model["max_phase_shift_hours"]))
        phase_shift = max(
            -max_shift,
            min(max_shift, _circular_hour_difference(desired_peak, reference_peak)),
        )

    coefs = model.get("circadian_coefs", ADAPTIVE_MODEL_DEFAULTS["circadian_coefs"])
    harmonics = int(model.get("harmonics", (len(coefs) - 1) // 2))
    if harmonics not in (2, 3) or len(coefs) != 1 + 2 * harmonics:
        coefs = ADAPTIVE_MODEL_DEFAULTS["circadian_coefs"]
        harmonics = 2
    theta = 2.0 * math.pi * ((clock_hour - phase_shift) % 24.0) / 24.0
    baseline = float(coefs[0])
    for harmonic in range(1, harmonics + 1):
        coefficient_index = 1 + (harmonic - 1) * 2
        baseline += float(coefs[coefficient_index]) * math.cos(harmonic * theta)
        baseline += float(coefs[coefficient_index + 1]) * math.sin(harmonic * theta)

    process_delta = max(
        float(model["homeostatic_delta_min"]),
        min(float(model["homeostatic_delta_max"]), process_delta),
    )
    debt_hours = max(0.0, float(sleep_debt_hours))
    debt_load = 1.0 - math.exp(
        -debt_hours / max(0.001, float(model["debt_saturation_hours"]))
    )
    inertia_load = math.exp(
        -hours_since_last_wake / max(0.001, float(model["inertia_tau"]))
    )
    energy_pct = (
        baseline
        - float(model["homeostatic_scale"]) * process_delta
        - float(model["debt_scale"]) * debt_load
        - float(model["inertia_scale"]) * inertia_load
    )
    alertness = energy_pct / 100.0
    return max(0.0, min(1.0, alertness)) if clamp else alertness


def personal_scaled_s_energy(
    t: float,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    clamp: bool = True,
    params: dict | None = None,
) -> float:
    """M3 fallback: two-process with scaled Process S (k_S). Returns 0.0–1.0."""
    if params is None:
        fit = load_personal_fit()
        params = (fit or {}).get("params", {})
    k_s = params.get("k_s", 1.0)
    raw = two_process_energy(
        t,
        sleep_debt_hours,
        sleep_duration,
        params.get("circadian_peak_offset", 10.0),
        clamp=False,
        tau_wake=params.get("tau_wake", 23.0),
        tau_sleep=params.get("tau_sleep", 4.0),
        tau_inertia=params.get("tau_inertia", 1.5),
        debt_factor=params.get("debt_factor", 1.0),
    )
    # Approximate scaling: reduce S contribution by adjusting toward circadian-only
    if k_s != 1.0:
        s_only = two_process_energy(t, 0.0, sleep_duration, 10.0, clamp=False,
                                    tau_wake=params.get("tau_wake", 23.0),
                                    tau_sleep=params.get("tau_sleep", 4.0),
                                    tau_inertia=0.01, debt_factor=0.0)
        c_only = two_process_energy(t, 0.0, sleep_duration, 10.0, clamp=False,
                                    tau_wake=999.0, tau_sleep=4.0,
                                    tau_inertia=params.get("tau_inertia", 1.5), debt_factor=0.0)
        raw = c_only - k_s * (c_only - s_only) - (sleep_debt_hours * params.get("debt_factor", 1.0) / 25.0)
    if clamp:
        return max(0.0, min(1.0, raw))
    return raw


def get_personal_energy(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    sleep_efficiency: float = 0.9,
    clamp: bool = True,
    clock_hour: float | None = None,
    wake_hour: float | None = None,
    bathyphase_hour: float | None = None,
    circadian_peak_offset: float | None = None,
    nap_intervals: list[tuple[float, float]] | tuple[tuple[float, float], ...] | None = None,
) -> float | None:
    """Returns personal-model energy if configured, else None."""
    fit = load_personal_fit()
    if not fit:
        return None
    model_id = fit.get("model_id", "")
    params = fit.get("params", {})
    if model_id in ADAPTIVE_MODEL_IDS and clock_hour is not None and wake_hour is not None:
        return adaptive_sleep_energy(
            clock_hour=clock_hour,
            wake_hour=wake_hour,
            sleep_duration=sleep_duration,
            sleep_debt_hours=sleep_debt_hours,
            bathyphase_hour=bathyphase_hour,
            circadian_peak_offset=circadian_peak_offset,
            nap_intervals=nap_intervals,
            params=params,
            clamp=clamp,
        )
    if model_id == "M7_empirical_clock_fourier" and clock_hour is not None:
        return personal_clock_fourier_energy(clock_hour, params, clamp)
    if model_id == "M4_circadian_daily_baseline":
        return personal_m4_energy(t, sleep_duration, sleep_debt_hours, sleep_efficiency, params, clamp)
    if model_id == "M3_scaled_process_s":
        return personal_scaled_s_energy(t, sleep_debt_hours, sleep_duration, clamp, params)
    if model_id == "M2_tuned_two_process":
        return two_process_energy(
            t, sleep_debt_hours, sleep_duration,
            params.get("circadian_peak_offset", 10.0), clamp,
            tau_wake=params.get("tau_wake", 23.0),
            tau_sleep=params.get("tau_sleep", 4.0),
            tau_inertia=params.get("tau_inertia", 1.5),
            debt_factor=params.get("debt_factor", 1.0),
        )
    return personal_m4_energy(t, sleep_duration, sleep_debt_hours, sleep_efficiency, params, clamp)

_energy_summary_printed = False

def get_energy_level(
    h_clock: float,
    wake_hour: float,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    bathyphase_hour: float = None,
    clamp: bool = True,
    tau_wake: float = 23.0,
    tau_sleep: float = 4.0,
    tau_inertia: float = 1.5,
    debt_factor: float = 1.0,
    circadian_peak_offset: float = None,
    sleep_efficiency: float = 0.9,
    use_personal_model: bool = True,
    nap_intervals: list[tuple[float, float]] | tuple[tuple[float, float], ...] | None = None,
) -> float:
    """
    Wrapper for UI that maps wall-clock time to hours-since-wake.
    Uses the configured personal empirical model when available.
    """
    global _energy_summary_printed
    
    t = (h_clock - wake_hour) % 24.0

    if use_personal_model:
        personal = get_personal_energy(
            t,
            sleep_duration,
            sleep_debt_hours,
            sleep_efficiency,
            clamp,
            clock_hour=h_clock,
            wake_hour=wake_hour,
            bathyphase_hour=bathyphase_hour,
            circadian_peak_offset=circadian_peak_offset,
            nap_intervals=nap_intervals,
        )
        if personal is not None:
            return personal

    # Calculate peak_offset
    if circadian_peak_offset is not None:
        peak_offset = circadian_peak_offset
    elif bathyphase_hour is not None:
        # WMZ peak is roughly 15 hours after bathyphase
        peak_h = (bathyphase_hour + 15.0) % 24
        peak_offset = (peak_h - wake_hour) % 24
    else:
        peak_offset = 13.0  # sensible population-average fallback (15h post-bathy, assuming 2h pre-wake nadir)

    if not _energy_summary_printed:
        # (Logging omitted for brevity in multi_replace, but in practice keep it)
        pass

    return two_process_energy(
        t, 
        sleep_debt_hours, 
        sleep_duration, 
        peak_offset, 
        clamp,
        tau_wake=tau_wake,
        tau_sleep=tau_sleep,
        tau_inertia=tau_inertia,
        debt_factor=debt_factor
    )


def predict_energy(
    h_clock: float,
    wake_hour: float,
    sleep_context: SleepModelContext | None,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    bathyphase_hour: float | None = None,
    clamp: bool = True,
    tau_wake: float = 23.0,
    tau_sleep: float = 4.0,
    tau_inertia: float = 1.5,
    debt_factor: float = 1.0,
    circadian_peak_offset: float | None = None,
    sleep_efficiency: float = 0.9,
    use_personal_model: bool = True,
) -> EnergyPrediction:
    """Return an awake energy value or an explicit sleep/unavailable state."""
    if sleep_context is None or not sleep_context.available:
        return EnergyPrediction(EnergyPredictionState.UNAVAILABLE)
    if any(interval.contains(h_clock) for interval in sleep_context.all_sleep_intervals):
        return EnergyPrediction(EnergyPredictionState.ASLEEP)

    value = get_energy_level(
        h_clock,
        wake_hour,
        sleep_debt_hours,
        sleep_duration,
        bathyphase_hour,
        clamp,
        tau_wake,
        tau_sleep,
        tau_inertia,
        debt_factor,
        circadian_peak_offset,
        sleep_efficiency,
        use_personal_model,
        sleep_context.modeled_nap_intervals,
    )
    return EnergyPrediction(EnergyPredictionState.AWAKE, value)


def sync_sleep_inclusion(
    excluded_dates: list[str] | None,
    explicit_dates: list[str] | None,
    raw_logs: list[dict],
    today_str: str,
) -> list[str]:
    """
    Apply sleep-table inclusion rules:
    - Past dates (T-1..T-14): always included unless user explicitly toggled off.
    - Today (T-0): included only when a main-sleep record exists, unless explicit.
    """
    from datetime import datetime, timedelta

    excluded = list(excluded_dates or [])
    explicit = set(explicit_dates or [])
    today = datetime.strptime(today_str, "%Y-%m-%d").date()
    main_dates = {
        log.get("dateOfSleep")
        for log in raw_logs
        if log.get("isMainSleep") and log.get("dateOfSleep")
    }

    for i in range(1, 15):
        d = (today - timedelta(days=i)).strftime("%Y-%m-%d")
        if d not in explicit and d in excluded:
            excluded.remove(d)

    if today_str not in explicit:
        if today_str in main_dates:
            if today_str in excluded:
                excluded.remove(today_str)
        elif today_str not in excluded:
            excluded.append(today_str)

    return excluded


def sleep_table_sessions(
    raw_logs: list[dict],
    date_str: str,
    include_naps: bool,
) -> list[tuple[str, dict | None]]:
    """Return ordered table sessions for a date: ('main'|'nap'|'empty', record)."""
    logs = [r for r in raw_logs if r.get("dateOfSleep") == date_str]
    main = next((s for s in logs if s.get("isMainSleep")), None)
    naps = sorted(
        [s for s in logs if not s.get("isMainSleep")],
        key=lambda s: s.get("startTime", ""),
    )
    rows: list[tuple[str, dict | None]] = []
    if main:
        rows.append(("main", main))
    elif not logs:
        rows.append(("empty", None))
    if include_naps:
        rows.extend(("nap", nap) for nap in naps)
    return rows


def sleep_model_context(
    raw_logs: list[dict],
    active_sleep_date: str | None,
    include_naps: bool = True,
) -> SleepModelContext:
    """Extract the main sleep, display masks, and modeled nap intervals."""
    if not active_sleep_date:
        return SleepModelContext(None)

    logs = [log for log in raw_logs if log.get("dateOfSleep") == active_sleep_date]
    main_sleep = next((log for log in logs if log.get("isMainSleep")), None)
    if not main_sleep:
        return SleepModelContext(None)

    try:
        from datetime import datetime

        def parse_local(value: str) -> datetime:
            parsed = datetime.fromisoformat(value.strip().removesuffix("Z"))
            return parsed.replace(tzinfo=None)

        main_start = parse_local(main_sleep["startTime"])
        wake_time = parse_local(main_sleep["endTime"])
    except (KeyError, TypeError, ValueError):
        return SleepModelContext(None)

    main_duration = max(0.0, float(main_sleep.get("minutesAsleep", 0)) / 60.0)
    def clock_hour(value: datetime) -> float:
        return value.hour + value.minute / 60.0 + value.second / 3600.0

    sleep_intervals: list[ClockSleepInterval] = [
        ClockSleepInterval(
            clock_hour(main_start),
            clock_hour(wake_time),
            SleepIntervalKind.MAIN,
        )
    ]

    naps: list[tuple[float, float]] = []
    for nap in logs:
        if nap.get("isMainSleep"):
            continue
        try:
            start = parse_local(nap["startTime"])
            end = parse_local(nap["endTime"])
        except (KeyError, TypeError, ValueError):
            continue
        sleep_intervals.append(
            ClockSleepInterval(
                clock_hour(start),
                clock_hour(end),
                SleepIntervalKind.NAP,
            )
        )
        start_after_wake = (start - wake_time).total_seconds() / 3600.0
        end_after_wake = (end - wake_time).total_seconds() / 3600.0
        if not include_naps or end_after_wake <= 0.0 or start_after_wake >= 24.0:
            continue
        naps.append((max(0.0, start_after_wake), min(24.0, end_after_wake)))

    return SleepModelContext(
        main_sleep_duration=main_duration,
        all_sleep_intervals=tuple(sleep_intervals),
        modeled_nap_intervals=_normalise_nap_intervals(naps),
    )


def compute_sleep_debt(
    sleep_logs: list[dict], 
    sleep_need_hours: float,
    include_naps: bool = True,
    excluded_dates: list[str] = None
) -> float:
    """Compute adaptive debt from observed nights in the T-0 through T-14 window.

    Missing and explicitly excluded dates carry the prior state unchanged rather
    than fabricating a zero-sleep night. Each observed night discounts prior debt
    by 10%; oversleep can repay debt, but the state never becomes negative.
    """
    if excluded_dates is None:
        excluded_dates = []

    # Group sleep by date
    daily_sleep = {}
    for log in sleep_logs:
        date = log.get("dateOfSleep")
        if not date or (not include_naps and not log.get("isMainSleep", False)):
            continue
        daily_sleep[date] = daily_sleep.get(date, 0.0) + (log.get("minutesAsleep", 0) / 60.0)

    from datetime import datetime, timedelta
    today = datetime.now()
    excluded = set(excluded_dates)
    debt = 0.0
    decay_factor = 0.9

    # Oldest to newest makes this a state update rather than a weighted sum.
    for i in range(14, -1, -1):
        date_str = (today - timedelta(days=i)).strftime("%Y-%m-%d")
        if date_str in excluded or date_str not in daily_sleep:
            continue
        deficit = float(sleep_need_hours) - daily_sleep[date_str]
        debt = max(0.0, debt * decay_factor + deficit)

    return debt

def find_bathyphase(intraday_hr: list[dict]) -> float | None:
    """
    Finds the bathyphase (lowest HR clock-hour) from Fitbit intraday HR data.
    Uses Parabolic Vertex Fit for sub-hour precision.
    """
    print(f"\n{'-'*50}")
    print(f"[find_bathyphase] intraday HR points received = {len(intraday_hr)}")

    if not intraday_hr:
        print("[find_bathyphase] WARNING: no HR data provided -> returning None")
        return None

    buckets: dict[int, list[float]] = {}
    for p in intraday_hr:
        h = int(p['time'].split(':')[0])
        buckets.setdefault(h, []).append(float(p['value']))

    if not buckets:
        return None

    print("[find_bathyphase] avg HR by hour during sleep window:")
    avg_by_hour: dict[int, float] = {}
    for h in sorted(buckets.keys()):
        avg = sum(buckets[h]) / len(buckets[h])
        avg_by_hour[h] = avg
        print(f"  {h:02d}:00  avg HR = {avg:.1f} bpm")

    min_h = min(avg_by_hour, key=avg_by_hour.get)
    
    # Parabolic Fit
    y2 = avg_by_hour[min_h]
    prev_h = (min_h - 1) % 24
    next_h = (min_h + 1) % 24
    
    y1 = avg_by_hour.get(prev_h)
    y3 = avg_by_hour.get(next_h)
    
    if y1 is not None and y3 is not None:
        denom = y1 - 2*y2 + y3
        if abs(denom) > 0.0001:
            offset = -0.5 * (y3 - y1) / denom
            if abs(offset) <= 1.0:
                result = (min_h + offset) % 24
                print(f"[find_bathyphase] RESULT = {result:.2f} (Parabolic Fit, offset={offset:.2f})")
                print(f"{'-'*50}\n")
                return result

    print(f"[find_bathyphase] RESULT = {min_h:.2f} (Simple Minimum)")
    print(f"{'-'*50}\n")

    return float(min_h)

class EnergyCurve:
    def __init__(self, canvas, color="#00A8FF"):
        self.canvas = canvas
        self.color  = color
        self.wake_hour         = 7.0
        self.sleep_debt_hours  = 0.0
        self.sleep_duration    = 7.5
        self.bathyphase_hour   = None
        self.nap_intervals     = ()
        self.sleep_context     = None
        self.normalize         = True
        
        # Model Parameters
        self.tau_wake     = 23.0
        self.tau_sleep    = 4.0
        self.tau_inertia  = 1.5
        self.debt_factor   = 1.0
        self.circadian_peak_offset = None # If None, use bathyphase logic

        # Cache variables
        self._cached_args = None
        self._cached_levels = []
        self._cached_e_min = 0.0
        self._cached_e_max = 0.0
        self._line_ids = []

    def interpolate_color(self, val: float) -> str:
        val = max(0.0, min(1.0, val))
        r = int(0   * (1 - val) + 255 * val)
        g = int(210 * (1 - val) + 75  * val)
        b = int(255 * (1 - val) + 43  * val)
        return f'#{r:02x}{g:02x}{b:02x}'

    def get_cached_energy(self, h: float, steps: int = 1440) -> float | None:
        """Returns the energy level at hour `h` using a precomputed cache."""
        args = (
            self.wake_hour, self.sleep_debt_hours, self.sleep_duration, self.bathyphase_hour,
            tuple(self.nap_intervals), self.sleep_context,
            self.tau_wake, self.tau_sleep, self.tau_inertia,
            self.debt_factor, self.circadian_peak_offset
        )
        if self._cached_args != args or not self._cached_levels or len(self._cached_levels) != steps + 1:
            self._recompute_cache(steps)

        if self.sleep_context is None or not self.sleep_context.available:
            return None
        if any(
            interval.contains(h)
            for interval in self.sleep_context.all_sleep_intervals
        ):
            return None
        idx = int(round((h % 24.0) / 24.0 * steps)) % steps
        value = self._cached_levels[idx]
        if value is not None:
            return value
        # Preserve exact half-open boundary semantics when the minute cache
        # rounds an awake timestamp onto a neighboring sleep sample.
        return predict_energy(
            h,
            self.wake_hour,
            self.sleep_context,
            self.sleep_debt_hours,
            self.sleep_duration,
            self.bathyphase_hour,
            clamp=False,
            tau_wake=self.tau_wake,
            tau_sleep=self.tau_sleep,
            tau_inertia=self.tau_inertia,
            debt_factor=self.debt_factor,
            circadian_peak_offset=self.circadian_peak_offset,
        ).value

    def get_cached_prediction(self, h: float, steps: int = 1440) -> EnergyPrediction:
        value = self.get_cached_energy(h, steps)
        if value is not None:
            return EnergyPrediction(EnergyPredictionState.AWAKE, value)
        if self.sleep_context and self.sleep_context.available and any(
            interval.contains(h) for interval in self.sleep_context.all_sleep_intervals
        ):
            return EnergyPrediction(EnergyPredictionState.ASLEEP)
        return EnergyPrediction(EnergyPredictionState.UNAVAILABLE)

    def _recompute_cache(self, steps: int):
        global _energy_summary_printed
        _energy_summary_printed = False
        levels = []
        for i in range(steps + 1):
            h = (i / float(steps)) * 24.0
            prediction = predict_energy(
                h,
                self.wake_hour,
                self.sleep_context,
                self.sleep_debt_hours,
                self.sleep_duration,
                self.bathyphase_hour,
                clamp=False,
                tau_wake=self.tau_wake,
                tau_sleep=self.tau_sleep,
                tau_inertia=self.tau_inertia,
                debt_factor=self.debt_factor,
                circadian_peak_offset=self.circadian_peak_offset,
            )
            levels.append(prediction.value)
        self._cached_levels = levels
        awake_levels = [level for level in levels if level is not None]
        self._cached_e_min = min(awake_levels) if awake_levels else 0.0
        self._cached_e_max = max(awake_levels) if awake_levels else 0.0
        self._cached_args = (
            self.wake_hour, self.sleep_debt_hours, self.sleep_duration, self.bathyphase_hour,
            tuple(self.nap_intervals), self.sleep_context,
            self.tau_wake, self.tau_sleep, self.tau_inertia,
            self.debt_factor, self.circadian_peak_offset
        )

    def get_display_radius(self, energy: float, radius: float) -> float:
        """Calculates the display radius for a given energy level and base radius."""
        e_min = self._cached_e_min
        e_max = self._cached_e_max
        e_range = e_max - e_min
        
        if self.normalize and e_range > 0.01:
            # Scale so the 24h cycle always spans 10% to 90% radius
            display_energy = (energy - e_min) / e_range
            return (0.10 + 0.80 * display_energy) * radius
        else:
            # Absolute mapping: clamp to 1.0, scale to 95% radius to avoid exceeding circumference
            clamped_energy = max(0.0, min(1.0, energy))
            return clamped_energy * (radius * 0.95)

    def draw(self, cx: float, cy: float, radius: float, wake_hour: float, draw_obj=None, width_scale=1.0):
        self.wake_hour = wake_hour
        
        # Trigger cache update if needed
        self.get_cached_energy(0)

        # Draw each awake segment independently so no line crosses sleep.
        max_step_hours = 24.0 / 72.0
        segment_index = 0
        intervals = self.sleep_context.all_sleep_intervals if self.sleep_context else ()
        for start, end in awake_clock_segments(intervals):
            length = end - start
            if length <= 1e-9:
                continue
            point_count = max(2, int(math.ceil(length / max_step_hours)) + 1)
            awake_end = math.nextafter(end, start)
            last_px = last_py = None
            for point_index in range(point_count):
                fraction = point_index / (point_count - 1)
                raw_hour = start + (awake_end - start) * fraction
                h = raw_hour % 24.0
                energy = self.get_cached_energy(h)
                if energy is None:
                    last_px = last_py = None
                    continue
                current_r = self.get_display_radius(energy, radius)

                angle = (18.0 - h) * 15.0
                rad = math.radians(angle)
                px = cx + current_r * math.cos(rad)
                py = cy - current_r * math.sin(rad)

                if last_px is not None:
                    color = self.interpolate_color(energy)
                    width = int(4 * width_scale)
                    if draw_obj:
                        draw_obj.line([(last_px, last_py), (px, py)], fill=color, width=width)
                    else:
                        item_id = self._line_ids[segment_index] if segment_index < len(self._line_ids) else None
                        try:
                            item_exists = item_id is not None and bool(self.canvas.type(item_id))
                        except tk.TclError:
                            item_exists = False
                        if item_exists:
                            self.canvas.coords(item_id, last_px, last_py, px, py)
                            self.canvas.itemconfigure(item_id, fill=color, width=width)
                        else:
                            item_id = self.canvas.create_line(
                                last_px, last_py, px, py,
                                fill=color,
                                width=width,
                                capstyle=tk.ROUND,
                                tags=("energy_curve", f"energy_curve_segment_{segment_index}"),
                            )
                            if segment_index < len(self._line_ids):
                                self._line_ids[segment_index] = item_id
                            else:
                                self._line_ids.append(item_id)
                        segment_index += 1
                last_px, last_py = px, py

        if draw_obj is None and len(self._line_ids) > segment_index:
            for item_id in self._line_ids[segment_index:]:
                try:
                    self.canvas.delete(item_id)
                except tk.TclError:
                    pass
            del self._line_ids[segment_index:]

    def _draw_indicator(self, cx, cy, radius, hour, color):
        angle = (18 - hour) * 15
        rad = math.radians(angle)

        triangle_size = radius * 0.12
        base_width = triangle_size * 0.8

        tip_r = radius - triangle_size
        base_r = radius

        # Tip point
        tx = cx + tip_r * math.cos(rad)
        ty = cy - tip_r * math.sin(rad)

        # Base points (perpendicular to the radial line)
        # rad + pi/2 gives the direction of the base line
        perp_rad = rad + math.pi/2
        dx = math.cos(perp_rad) * (base_width / 2)
        dy = -math.sin(perp_rad) * (base_width / 2)

        bx = cx + base_r * math.cos(rad)
        by = cy - base_r * math.sin(rad)

        x1, y1 = bx + dx, by + dy
        x2, y2 = bx - dx, by - dy

        self.canvas.create_polygon(
            tx, ty, x1, y1, x2, y2,
            fill=color, outline="", tags="energy_indicators"
        )

    def draw_bathyphase_indicator(self, cx, cy, radius, bathy_hour, energy_val=None):
        if bathy_hour is None: return
        # Bathyphase represents the lowest theoretical energy color, regardless
        # of today's sleep/debt-adjusted curve.
        color = self.interpolate_color(0.0)
        self._draw_indicator(cx, cy, radius, bathy_hour, color)

    def draw_acrophase_indicator(self, cx, cy, radius, acro_hour, energy_val):
        if acro_hour is None: return
        # Acrophase represents the highest theoretical energy color, regardless
        # of today's sleep/debt-adjusted curve.
        color = self.interpolate_color(1.0)
        self._draw_indicator(cx, cy, radius, acro_hour, color)
