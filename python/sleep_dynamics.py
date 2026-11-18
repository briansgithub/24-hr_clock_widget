"""
Active sleep restoration model (Process S discharge + nap/main wake carryover).

Sleep is not missing data: homeostatic pressure discharges during sleep and
reshapes post-wake predictions (main sleep or nap).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from analyze_energy_fit import debt_as_of_date
from energy_models import ModelParams, predict_m4_circadian_baseline

S_MAX = 1.0
S_MIN = 0.05


@dataclass
class SleepEpisode:
    start: datetime
    end: datetime
    is_main: bool
    duration_hours: float
    efficiency: float
    date: str


@dataclass
class DynamicsState:
    point_dt: datetime
    in_sleep: bool
    hours_since_main_wake: float
    hours_since_last_wake: float
    s_pressure: float
    recovery_fraction: float
    nap_inertia: float
    main_wake_dt: datetime | None
    last_wake_dt: datetime | None
    sleep_duration: float
    sleep_efficiency: float
    sleep_debt_hours: float
    active_episode: SleepEpisode | None = None


def _parse_iso(iso_str: str) -> datetime:
    cleaned = iso_str.replace("Z", "")
    if "." in cleaned:
        cleaned = cleaned.split(".")[0]
    return datetime.fromisoformat(cleaned)


def build_sleep_episodes(sleep_logs: list[dict]) -> list[SleepEpisode]:
    episodes: list[SleepEpisode] = []
    for log in sleep_logs:
        start_s = log.get("startTime")
        end_s = log.get("endTime")
        if not start_s or not end_s:
            continue
        start = _parse_iso(start_s)
        end = _parse_iso(end_s)
        bed = log.get("timeInBed", 0)
        asleep = log.get("minutesAsleep", 0)
        eff = (asleep / bed) if bed > 0 else 0.92
        episodes.append(
            SleepEpisode(
                start=start,
                end=end,
                is_main=bool(log.get("isMainSleep", False)),
                duration_hours=asleep / 60.0,
                efficiency=eff,
                date=str(log.get("dateOfSleep", start.date().isoformat())),
            )
        )
    return sorted(episodes, key=lambda e: e.start)


def merge_sleep_episodes(
    fitbit_episodes: list[SleepEpisode],
    inferred_episodes: list[SleepEpisode],
) -> list[SleepEpisode]:
    """Combine Fitbit and log-inferred sleep; drop inferred blocks fully inside Fitbit."""
    merged = list(fitbit_episodes)
    for inf in inferred_episodes:
        covered = any(
            inf.start >= fb.start and inf.end <= fb.end
            for fb in fitbit_episodes
        )
        if not covered:
            merged.append(inf)
    return sorted(merged, key=lambda e: e.start)


def s0_after_main_sleep(
    sleep_duration: float,
    tau_wake: float = 23.0,
    tau_sleep: float = 4.0,
) -> float:
    """Residual homeostatic pressure immediately after a main sleep bout."""
    sleep_start = 24.0 - sleep_duration
    e_w = math.exp(-sleep_start / tau_wake)
    e_s = math.exp(-sleep_duration / tau_sleep)
    divisor = max(1e-4, 1.0 - e_w * e_s)
    return (S_MAX * e_s * (1.0 - e_w) + S_MIN * (1.0 - e_s)) / divisor


def s_build_while_awake(
    s_at_wake: float,
    hours_awake: float,
    tau_wake: float,
) -> float:
    if hours_awake <= 0:
        return s_at_wake
    return s_at_wake + (S_MAX - s_at_wake) * (1.0 - math.exp(-hours_awake / tau_wake))


def s_discharge_while_asleep(
    s_at_onset: float,
    hours_asleep: float,
    tau_sleep: float,
) -> float:
    if hours_asleep <= 0:
        return s_at_onset
    return S_MIN + (s_at_onset - S_MIN) * math.exp(-hours_asleep / tau_sleep)


def recovery_fraction(s_before: float, s_after: float) -> float:
    span = max(1e-6, s_before - S_MIN)
    return max(0.0, min(1.0, (s_before - s_after) / span))


def nap_inertia_penalty(
    s_after_nap: float,
    hours_since_nap_wake: float,
    tau_inertia: float,
    circ_offset: float = 10.0,
) -> float:
    """Short post-nap grogginess scaled by residual pressure (0–1 alertness units)."""
    c_at_wake = 1.10 + 0.55 * math.cos((math.pi / 12.0) * (-circ_offset))
    raw_at_wake = c_at_wake - s_after_nap
    return max(0.0, raw_at_wake) * math.exp(-hours_since_nap_wake / max(0.25, tau_inertia))


def _main_wake_before(point_dt: datetime, episodes: list[SleepEpisode]) -> SleepEpisode | None:
    mains = [e for e in episodes if e.is_main and e.end <= point_dt]
    if not mains:
        return None
    return mains[-1]


def dynamics_at(
    point_dt: datetime,
    sleep_logs: list[dict],
    sleep_need_hours: float,
    params: ModelParams | None = None,
    extra_episodes: list[SleepEpisode] | None = None,
    raw_df: Any | None = None,
    excluded_dates: list[str] | None = None,
) -> DynamicsState | None:
    """
    Walk the sleep/wake timeline up to point_dt.
    Returns active restoration state (including in-sleep recovery progress).
    """
    p = params or ModelParams()
    inferred: list[SleepEpisode] = []
    if raw_df is not None:
        from empirical_sleep import build_inferred_sleep_episodes, merge_fitbit_and_inferred_episodes

        inferred = build_inferred_sleep_episodes(raw_df)
        episodes = merge_fitbit_and_inferred_episodes(sleep_logs, inferred)
    else:
        episodes = merge_sleep_episodes(
            build_sleep_episodes(sleep_logs),
            extra_episodes or [],
        )
    main_wake = _main_wake_before(point_dt, episodes)
    if main_wake is None:
        return None

    s = s0_after_main_sleep(main_wake.duration_hours, p.tau_wake, p.tau_sleep)
    main_wake_dt = main_wake.end
    last_wake_dt = main_wake.end
    nap_inertia = 0.0
    in_sleep = False
    rec_frac = 0.0
    active: SleepEpisode | None = None

    following = [
        e for e in episodes
        if e.start >= main_wake_dt and e.start < point_dt
    ]

    for ep in following:
        awake_h = (ep.start - last_wake_dt).total_seconds() / 3600.0
        if awake_h > 0:
            s = s_build_while_awake(s, awake_h, p.tau_wake)

        if point_dt < ep.end:
            asleep_h = (point_dt - ep.start).total_seconds() / 3600.0
            s_before = s
            s = s_discharge_while_asleep(s, asleep_h, p.tau_sleep)
            in_sleep = True
            rec_frac = recovery_fraction(s_before, s)
            active = ep
            last_wake_dt = ep.start
            break

        asleep_h = (ep.end - ep.start).total_seconds() / 3600.0
        s = s_discharge_while_asleep(s, asleep_h, p.tau_sleep)
        last_wake_dt = ep.end
        if ep.is_main:
            s = s0_after_main_sleep(ep.duration_hours, p.tau_wake, p.tau_sleep)
            main_wake_dt = ep.end
            main_wake = ep
            nap_inertia = 0.0
        else:
            hours_after = 0.0
            nap_inertia = nap_inertia_penalty(
                s, hours_after, p.tau_inertia, p.circ_offset
            )

    if not in_sleep:
        awake_h = (point_dt - last_wake_dt).total_seconds() / 3600.0
        s = s_build_while_awake(s, awake_h, p.tau_wake)
        if last_wake_dt != main_wake_dt:
            hours_after_nap = (point_dt - last_wake_dt).total_seconds() / 3600.0
            nap_inertia = nap_inertia_penalty(
                s, hours_after_nap, p.tau_inertia, p.circ_offset
            )

    debt = debt_as_of_date(
        sleep_logs,
        main_wake.date,
        sleep_need_hours,
        include_naps=True,
        excluded_dates=excluded_dates,
    )
    hours_main = (point_dt - main_wake_dt).total_seconds() / 3600.0
    hours_last = (point_dt - last_wake_dt).total_seconds() / 3600.0

    return DynamicsState(
        point_dt=point_dt,
        in_sleep=in_sleep,
        hours_since_main_wake=max(0.0, hours_main),
        hours_since_last_wake=max(0.0, hours_last),
        s_pressure=float(s),
        recovery_fraction=float(rec_frac),
        nap_inertia=float(nap_inertia),
        main_wake_dt=main_wake_dt,
        last_wake_dt=last_wake_dt,
        sleep_duration=float(main_wake.duration_hours),
        sleep_efficiency=float(main_wake.efficiency),
        sleep_debt_hours=float(debt),
        active_episode=active,
    )


def s_penalty_energy(s_pressure: float, k_s: float = 1.0) -> float:
    """Map homeostatic pressure (0–1) to subjective energy deduction (0–35)."""
    return min(35.0, k_s * s_pressure * 35.0)


def predict_energy_sleep_aware(
    point_dt: datetime,
    sleep_logs: list[dict],
    sleep_need_hours: float,
    params: ModelParams | None = None,
    nap_events: list[dict[str, Any]] | None = None,
    fourier_coefs: list[float] | None = None,
    extra_episodes: list[SleepEpisode] | None = None,
    raw_df: Any | None = None,
    excluded_dates: list[str] | None = None,
) -> dict[str, Any]:
    """
    M4 subjective energy adjusted by active sleep dynamics.
    During sleep returns restoration metadata (no subjective rating).
    """
    _ = nap_events  # timeline built from sleep_logs
    if excluded_dates is None and raw_df is not None:
        from empirical_sleep import load_excluded_dates

        excluded_dates = load_excluded_dates()
    dyn = dynamics_at(
        point_dt,
        sleep_logs,
        sleep_need_hours,
        params,
        extra_episodes=extra_episodes,
        raw_df=raw_df,
        excluded_dates=excluded_dates,
    )
    if dyn is None:
        return {"energy": None, "in_sleep": True, "reason": "no_wake_context"}

    p = params or ModelParams()
    t_model = dyn.hours_since_main_wake % 24.0

    if dyn.in_sleep:
        proj = projected_wake_energy(dyn, sleep_logs, sleep_need_hours, p, fourier_coefs)
        return {
            "energy": None,
            "in_sleep": True,
            "s_pressure": dyn.s_pressure,
            "recovery_fraction": dyn.recovery_fraction,
            "projected_wake_energy": proj,
            "active_episode_main": bool(dyn.active_episode and dyn.active_episode.is_main),
        }

    m4 = predict_m4_circadian_baseline(
        t_model,
        dyn.sleep_duration,
        dyn.sleep_debt_hours,
        dyn.sleep_efficiency,
        params=p,
    )
    penalty = s_penalty_energy(dyn.s_pressure, p.k_s)
    inertia_pts = dyn.nap_inertia * 25.0
    energy = max(0.0, min(100.0, m4 - penalty - inertia_pts))

    if fourier_coefs and len(fourier_coefs) >= 5:
        from energy_models import predict_fourier_multi_peak

        fp = ModelParams(**{**p.to_dict(), "fourier_coefs": fourier_coefs})
        fur = predict_fourier_multi_peak(t_model, params=fp, n_harmonics=4)
        energy = max(0.0, min(100.0, 0.35 * fur + 0.65 * energy))

    return {
        "energy": energy,
        "in_sleep": False,
        "s_pressure": dyn.s_pressure,
        "recovery_fraction": 0.0,
        "projected_wake_energy": None,
        "hours_since_main_wake": dyn.hours_since_main_wake,
        "nap_inertia": dyn.nap_inertia,
    }


def projected_wake_energy(
    dyn: DynamicsState,
    sleep_logs: list[dict],
    sleep_need_hours: float,
    params: ModelParams | None = None,
    fourier_coefs: list[float] | None = None,
    minutes_after_wake: float = 20.0,
    extra_episodes: list[SleepEpisode] | None = None,
) -> float | None:
    """Energy predicted shortly after the active sleep episode ends."""
    if not dyn.in_sleep or dyn.active_episode is None:
        return None
    from datetime import timedelta

    wake_dt = dyn.active_episode.end + timedelta(minutes=minutes_after_wake)
    result = predict_energy_sleep_aware(
        wake_dt, sleep_logs, sleep_need_hours, params, fourier_coefs=fourier_coefs,
        extra_episodes=extra_episodes,
    )
    energy = result.get("energy")
    return round(float(energy), 1) if energy is not None else None
