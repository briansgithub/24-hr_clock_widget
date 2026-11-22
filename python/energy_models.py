"""
Candidate energy-curve models (M1–M6) for empirical fit analysis.

All models predict subjective energy on a 0–100 scale.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field, asdict
from typing import Any

import numpy as np

from energy_logic import two_process_energy


@dataclass
class ModelParams:
    """Tunable parameters shared across two-process variants."""

    tau_wake: float = 23.0
    tau_sleep: float = 4.0
    tau_inertia: float = 1.5
    circadian_peak_offset: float = 10.0
    amp_primary: float = 0.55
    amp_secondary: float = -0.15
    c_base: float = 1.10
    debt_factor: float = 1.0
    k_s: float = 1.0
    debt_alpha: float = 0.0
    # M4 circadian + daily baseline
    circ_amp: float = 15.0
    circ_amp2: float = 5.0
    circ_offset: float = 8.0
    baseline_intercept: float = 50.0
    baseline_sleep_coef: float = 2.0
    baseline_debt_coef: float = -1.5
    baseline_efficiency_coef: float = 10.0
    # M5 Fourier
    fourier_coefs: list[float] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ModelParams:
        known = {f.name for f in cls.__dataclass_fields__.values()}  # type: ignore[attr-defined]
        return cls(**{k: v for k, v in data.items() if k in known})


def _process_s(
    t: float,
    sleep_duration: float,
    tau_wake: float,
    tau_sleep: float,
) -> float:
    s_max, s_min = 1.0, 0.05
    sleep_start = 24.0 - sleep_duration
    e_w = math.exp(-sleep_start / tau_wake)
    e_s = math.exp(-sleep_duration / tau_sleep)
    divisor = max(1e-4, 1.0 - e_w * e_s)
    s0 = (s_max * e_s * (1.0 - e_w) + s_min * (1.0 - e_s)) / divisor
    s_bed = s0 * e_w + s_max * (1.0 - e_w)
    if t < sleep_start:
        return s0 + (s_max - s0) * (1.0 - math.exp(-t / tau_wake))
    return s_min + (s_bed - s_min) * math.exp(-(t - sleep_start) / tau_sleep)


def _process_c(
    t: float,
    circadian_peak_offset: float,
    amp_primary: float,
    amp_secondary: float,
    c_base: float,
) -> float:
    c_primary = amp_primary * math.cos((math.pi / 12.0) * (t - circadian_peak_offset))
    c_secondary = amp_secondary * math.cos((math.pi / 6.0) * (t - 8.0))
    return c_base + c_primary + c_secondary


def _sleep_inertia(
    t: float,
    s0: float,
    circadian_peak_offset: float,
    amp_primary: float,
    amp_secondary: float,
    c_base: float,
    tau_inertia: float,
) -> float:
    c_at_wake = _process_c(0.0, circadian_peak_offset, amp_primary, amp_secondary, c_base)
    raw_at_wake = c_at_wake - s0
    return max(0.0, raw_at_wake) * math.exp(-t / tau_inertia)


def _s0_at_wake(sleep_duration: float, tau_wake: float, tau_sleep: float) -> float:
    s_max, s_min = 1.0, 0.05
    sleep_start = 24.0 - sleep_duration
    e_w = math.exp(-sleep_start / tau_wake)
    e_s = math.exp(-sleep_duration / tau_sleep)
    divisor = max(1e-4, 1.0 - e_w * e_s)
    return (s_max * e_s * (1.0 - e_w) + s_min * (1.0 - e_s)) / divisor


def predict_m1_baseline(
    t: float,
    sleep_duration: float = 7.5,
    sleep_debt_hours: float = 0.0,
    **_kwargs: Any,
) -> float:
    """M1: current production defaults via energy_logic."""
    return two_process_energy(
        t,
        sleep_debt_hours=sleep_debt_hours,
        sleep_duration=sleep_duration,
        circadian_peak_offset=10.0,
        clamp=False,
    ) * 100.0


def predict_m2_tuned(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    params: ModelParams,
    **_kwargs: Any,
) -> float:
    """M2: two-process with tunable harmonics and taus."""
    s_t = _process_s(t, sleep_duration, params.tau_wake, params.tau_sleep)
    c_t = _process_c(
        t,
        params.circadian_peak_offset,
        params.amp_primary,
        params.amp_secondary,
        params.c_base,
    )
    s0 = _s0_at_wake(sleep_duration, params.tau_wake, params.tau_sleep)
    inertia = _sleep_inertia(
        t,
        s0,
        params.circadian_peak_offset,
        params.amp_primary,
        params.amp_secondary,
        params.c_base,
        params.tau_inertia,
    )
    debt_penalty = min(0.35, (sleep_debt_hours * params.debt_factor) / 25.0)
    return max(0.0, (c_t - s_t - inertia - debt_penalty) * 100.0)


def predict_m3_scaled_s(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    params: ModelParams,
    **_kwargs: Any,
) -> float:
    """M3: alertness = C(t) - k_S * S(t) - W(t) - debt."""
    s_t = _process_s(t, sleep_duration, params.tau_wake, params.tau_sleep)
    c_t = _process_c(
        t,
        params.circadian_peak_offset,
        params.amp_primary,
        params.amp_secondary,
        params.c_base,
    )
    s0 = _s0_at_wake(sleep_duration, params.tau_wake, params.tau_sleep)
    inertia = _sleep_inertia(
        t,
        s0,
        params.circadian_peak_offset,
        params.amp_primary,
        params.amp_secondary,
        params.c_base,
        params.tau_inertia,
    )
    debt_penalty = min(0.35, (sleep_debt_hours * params.debt_factor) / 25.0)
    return max(0.0, (c_t - params.k_s * s_t - inertia - debt_penalty) * 100.0)


def predict_m4_circadian_baseline(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    sleep_efficiency: float = 0.9,
    params: ModelParams | None = None,
    **_kwargs: Any,
) -> float:
    """M4: daily baseline from sleep features + circadian shape."""
    p = params or ModelParams()
    baseline = (
        p.baseline_intercept
        + p.baseline_sleep_coef * sleep_duration
        + p.baseline_debt_coef * sleep_debt_hours
        + p.baseline_efficiency_coef * sleep_efficiency
    )
    shape = (
        p.circ_amp * math.cos((math.pi / 12.0) * (t - p.circ_offset))
        + p.circ_amp2 * math.cos((math.pi / 6.0) * (t - 8.0))
    )
    return max(0.0, min(100.0, baseline + shape))


def predict_m5_fourier(
    t: float,
    day_beta: float = 0.0,
    params: ModelParams | None = None,
    **_kwargs: Any,
) -> float:
    """M5: day intercept + 2-harmonic Fourier on hours-since-wake."""
    p = params or ModelParams()
    coefs = p.fourier_coefs
    if len(coefs) < 5:
        coefs = [50.0, 0.0, 0.0, 0.0, 0.0]
    omega = 2.0 * math.pi / 24.0
    val = coefs[0] + day_beta
    val += coefs[1] * math.cos(omega * t) + coefs[2] * math.sin(omega * t)
    val += coefs[3] * math.cos(2 * omega * t) + coefs[4] * math.sin(2 * omega * t)
    return max(0.0, min(100.0, val))


def predict_m6_time_varying_debt(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    params: ModelParams,
    **_kwargs: Any,
) -> float:
    """M6: debt_penalty(t) = base_debt * (1 + alpha * S(t))."""
    s_t = _process_s(t, sleep_duration, params.tau_wake, params.tau_sleep)
    c_t = _process_c(
        t,
        params.circadian_peak_offset,
        params.amp_primary,
        params.amp_secondary,
        params.c_base,
    )
    s0 = _s0_at_wake(sleep_duration, params.tau_wake, params.tau_sleep)
    inertia = _sleep_inertia(
        t,
        s0,
        params.circadian_peak_offset,
        params.amp_primary,
        params.amp_secondary,
        params.c_base,
        params.tau_inertia,
    )
    base_debt = min(0.35, (sleep_debt_hours * params.debt_factor) / 25.0)
    debt_penalty = base_debt * (1.0 + params.debt_alpha * s_t)
    return max(0.0, (c_t - s_t - inertia - debt_penalty) * 100.0)


MODEL_REGISTRY: dict[str, Any] = {
    "M1_baseline": predict_m1_baseline,
    "M2_tuned_two_process": predict_m2_tuned,
    "M3_scaled_process_s": predict_m3_scaled_s,
    "M4_circadian_daily_baseline": predict_m4_circadian_baseline,
    "M5_mixed_fourier": predict_m5_fourier,
    "M6_time_varying_debt": predict_m6_time_varying_debt,
}

DEFAULT_PARAM_BOUNDS: dict[str, tuple[tuple[float, ...], tuple[float, ...]]] = {
    "M2": (
        (8.0, 1.0, 0.2, 4.0, 0.05, -0.6, 0.4, 0.0),
        (40.0, 12.0, 5.0, 18.0, 1.2, 0.4, 2.0, 15.0),
    ),
    "M3": (
        (8.0, 1.0, 0.2, 4.0, 0.05, -0.6, 0.4, 0.0, 0.0),
        (40.0, 12.0, 5.0, 18.0, 1.2, 0.4, 2.0, 15.0, 1.5),
    ),
    "M4": (
        (20.0, -20.0, 4.0, -5.0, -5.0, -30.0, -30.0),
        (80.0, 20.0, 14.0, 5.0, 5.0, 30.0, 30.0),
    ),
    "M6": (
        (8.0, 1.0, 0.2, 4.0, 0.05, -0.6, 0.4, 0.0, 0.0),
        (40.0, 12.0, 5.0, 18.0, 1.2, 0.4, 2.0, 15.0, 2.0),
    ),
}


def pack_m2_params(x: np.ndarray) -> ModelParams:
    return ModelParams(
        tau_wake=x[0],
        tau_sleep=x[1],
        tau_inertia=x[2],
        circadian_peak_offset=x[3],
        amp_primary=x[4],
        amp_secondary=x[5],
        c_base=x[6],
        debt_factor=x[7],
    )


def pack_m3_params(x: np.ndarray) -> ModelParams:
    p = pack_m2_params(x[:8])
    p.k_s = x[8]
    return p


def pack_m4_params(x: np.ndarray) -> ModelParams:
    return ModelParams(
        baseline_intercept=x[0],
        circ_amp=x[1],
        circ_offset=x[2],
        circ_amp2=x[3],
        baseline_sleep_coef=x[4],
        baseline_debt_coef=x[5],
        baseline_efficiency_coef=x[6],
    )


def pack_m6_params(x: np.ndarray) -> ModelParams:
    p = pack_m2_params(x[:8])
    p.debt_alpha = x[8]
    return p


def metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, float]:
    err = y_true - y_pred
    ss_res = float(np.sum(err ** 2))
    ss_tot = float(np.sum((y_true - np.mean(y_true)) ** 2))
    return {
        "mae": float(np.mean(np.abs(err))),
        "rmse": float(np.sqrt(np.mean(err ** 2))),
        "bias": float(np.mean(err)),
        "r2": float(1.0 - ss_res / ss_tot) if ss_tot > 1e-9 else 0.0,
        "n": int(len(y_true)),
    }


def predict_fourier_multi_peak(
    t: float,
    day_beta: float = 0.0,
    params: ModelParams | None = None,
    n_harmonics: int = 4,
    **_kwargs: Any,
) -> float:
    """
    Fourier shape on hours-since-wake (up to 4 harmonics) for multiple peaks/troughs.
    Falls back to fewer coefficients if the fit vector is shorter.
    """
    p = params or ModelParams()
    coefs = list(p.fourier_coefs) if p.fourier_coefs else []
    while len(coefs) < 1 + 2 * n_harmonics:
        coefs.append(0.0)
    val = coefs[0] + day_beta
    omega = 2.0 * math.pi / 24.0
    for h in range(1, n_harmonics + 1):
        ci = 2 * h - 1
        si = 2 * h
        val += coefs[ci] * math.cos(h * omega * t) + coefs[si] * math.sin(h * omega * t)
    return max(0.0, min(100.0, val))


def nap_recovery_boost(
    point_dt: Any,
    nap_events: list[dict[str, Any]],
    max_hours_after: float = 3.0,
    tau_decay: float = 1.5,
) -> float:
    """Energy boost (0–15 pts) from recent naps ending before point_dt."""
    boost = 0.0
    for nap in nap_events:
        nap_end = nap.get("end_datetime")
        if nap_end is None or point_dt < nap_end:
            continue
        hours_after = (point_dt - nap_end).total_seconds() / 3600.0
        if hours_after < 0 or hours_after > max_hours_after:
            continue
        dur_h = float(nap.get("duration_hours", 0.5))
        boost += min(15.0, 4.0 * dur_h) * math.exp(-hours_after / tau_decay)
    return min(20.0, boost)


def predict_m4_nap_adjusted(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
    sleep_efficiency: float,
    params: ModelParams | None,
    point_dt: Any,
    nap_events: list[dict[str, Any]],
    fourier_coefs: list[float] | None = None,
    day_beta: float = 0.0,
    sleep_logs: list[dict] | None = None,
    sleep_need_hours: float = 8.0,
    extra_episodes: list[Any] | None = None,
    raw_df: Any | None = None,
) -> float:
    """M4 baseline + Fourier + sleep-dynamics S penalty and nap recovery."""
    if sleep_logs is not None and point_dt is not None:
        from sleep_dynamics import predict_energy_sleep_aware

        result = predict_energy_sleep_aware(
            point_dt,
            sleep_logs,
            sleep_need_hours,
            params,
            nap_events,
            fourier_coefs=fourier_coefs,
            extra_episodes=extra_episodes,
            raw_df=raw_df,
        )
        if result.get("energy") is not None:
            return float(result["energy"])

    m4_base = predict_m4_circadian_baseline(
        t, sleep_duration, sleep_debt_hours, sleep_efficiency, params=params
    )
    if fourier_coefs and len(fourier_coefs) >= 5:
        p = params or ModelParams()
        p = ModelParams(**{**p.to_dict(), "fourier_coefs": fourier_coefs})
        fourier_val = predict_fourier_multi_peak(t, day_beta=day_beta, params=p, n_harmonics=4)
        base = max(0.0, min(100.0, 0.35 * fourier_val + 0.65 * m4_base))
    else:
        base = m4_base
    return max(0.0, min(100.0, base + nap_recovery_boost(point_dt, nap_events)))


def find_energy_extrema(
    t_values: list[float] | np.ndarray,
    energy_values: list[float] | np.ndarray,
    wake_hour: float,
    min_prominence: float = 4.0,
    window: int = 5,
) -> list[dict[str, Any]]:
    """
    Detect local peaks and troughs on a predicted energy curve.
    Uses scipy prominence when available (handles broad M4 plateaus); falls back to
    windowed local extrema, then global max/min if the curve is monotonic.
    """
    t_arr = np.asarray(t_values, dtype=float)
    y_arr = np.asarray(energy_values, dtype=float)
    if len(t_arr) < 3:
        return []

    span = float(np.max(y_arr) - np.min(y_arr))
    prom = max(1.0, min(min_prominence, 0.05 * span)) if span > 0 else min_prominence
    min_dist = max(2, window // 2)
    extrema: list[dict[str, Any]] = []

    def _append(idx: int, kind: str, prominence: float) -> None:
        t_mid = float(t_arr[idx])
        extrema.append({
            "type": kind,
            "t": t_mid,
            "clock_hour": (wake_hour + t_mid) % 24.0,
            "energy": float(y_arr[idx]),
            "prominence": float(prominence),
        })

    try:
        from scipy.signal import find_peaks

        peaks, peak_props = find_peaks(y_arr, prominence=prom, distance=min_dist)
        troughs, trough_props = find_peaks(-y_arr, prominence=prom, distance=min_dist)
        for j, i in enumerate(peaks):
            _append(int(i), "peak", float(peak_props["prominences"][j]))
        for j, i in enumerate(troughs):
            _append(int(i), "trough", float(trough_props["prominences"][j]))
    except ImportError:
        half = max(1, window // 2)
        for i in range(half, len(y_arr) - half):
            mid = float(y_arr[i])
            left = float(y_arr[i - half])
            right = float(y_arr[i + half])
            peak_prom = mid - min(left, right)
            trough_prom = max(left, right) - mid
            if mid >= float(np.max(y_arr[i - half : i + half + 1])) - 1e-6 and peak_prom >= prom:
                _append(i, "peak", peak_prom)
            elif mid <= float(np.min(y_arr[i - half : i + half + 1])) + 1e-6 and trough_prom >= prom:
                _append(i, "trough", trough_prom)

    if not extrema and len(y_arr) >= 2:
        imax = int(np.argmax(y_arr))
        imin = int(np.argmin(y_arr))
        _append(imax, "peak", float(y_arr[imax] - np.min(y_arr)))
        if imin != imax:
            _append(imin, "trough", float(np.max(y_arr) - y_arr[imin]))

    extrema.sort(key=lambda e: e["t"])
    return extrema
