"""
Infer sleep/wake inputs from the empirical alertness log when Fitbit rows are missing.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timedelta
from typing import Any

import pandas as pd

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATUS_LOGGED = "LOGGED"
STATUS_SLEEP = "SLEEP_EXCLUDED"

DEFAULT_SLEEP_SETTINGS = os.path.join(REPO_ROOT, "python", "sleep_settings.json")
DEFAULT_LOG_EXCLUSIONS = os.path.join(REPO_ROOT, "python", "energy_log_exclusions.json")
SLOT_MINUTES = 30.0


def load_excluded_log_datetimes(path: str = DEFAULT_LOG_EXCLUSIONS) -> set[str]:
    """LOGGED timestamps excluded from fit after manual outlier review."""
    if not os.path.exists(path):
        return set()
    try:
        with open(path, "r", encoding="utf-8") as f:
            return set(json.load(f).get("excluded_datetimes", []))
    except (json.JSONDecodeError, OSError):
        return set()


def load_excluded_dates(path: str = DEFAULT_SLEEP_SETTINGS) -> list[str]:
    """Dates excluded from debt calculation (matches widget sleep_settings.json)."""
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            return list(json.load(f).get("excluded_dates", []))
    except (json.JSONDecodeError, OSError):
        return []


def _longest_sleep_run(sleep_hours: list[float], slot_minutes: float = SLOT_MINUTES) -> list[float]:
    if not sleep_hours:
        return []
    hours = sorted(sleep_hours)
    step = slot_minutes / 60.0
    best: list[float] = []
    current = [hours[0]]
    for h in hours[1:]:
        if h - current[-1] <= step + 0.01:
            current.append(h)
        else:
            if len(current) > len(best):
                best = current
            current = [h]
    if len(current) > len(best):
        best = current
    return best


def infer_wake_from_sleep_excluded(
    day_df: pd.DataFrame,
    slot_minutes: float = SLOT_MINUTES,
) -> float | None:
    """
    Estimate wake clock-hour from SLEEP_EXCLUDED blocks.
    Uses the longest sleep run and the first LOGGED point after it ends.
    """
    sleep = day_df[day_df["Status"] == STATUS_SLEEP]
    if sleep.empty:
        return None

    slot_h = slot_minutes / 60.0
    main_run = _longest_sleep_run(sleep["hour"].tolist(), slot_minutes)
    if not main_run:
        return None

    sleep_end = main_run[-1] + slot_h
    after = day_df[
        (day_df["hour"] >= sleep_end - 0.01)
        & (day_df["Status"] == STATUS_LOGGED)
        & day_df["EnergyLevel"].notna()
    ].sort_values("hour")
    if not after.empty:
        return float(after.iloc[0]["hour"])

    candidate = sleep_end % 24.0
    if candidate < 1.0:
        morning = day_df[
            (day_df["Status"] == STATUS_LOGGED)
            & (day_df["hour"] >= 5.0)
            & (day_df["hour"] < 14.0)
            & day_df["EnergyLevel"].notna()
        ].sort_values("hour")
        if not morning.empty:
            return float(morning.iloc[0]["hour"])
    return candidate


def infer_sleep_duration_from_day(
    day_df: pd.DataFrame,
    slot_minutes: float = SLOT_MINUTES,
) -> float:
    """Hours asleep from the longest SLEEP_EXCLUDED run on this calendar day."""
    sleep = day_df[day_df["Status"] == STATUS_SLEEP]
    if sleep.empty:
        return 7.5
    main_run = _longest_sleep_run(sleep["hour"].tolist(), slot_minutes)
    if not main_run:
        return 7.5
    hours = len(main_run) * (slot_minutes / 60.0)
    return max(3.0, min(12.0, hours))


def infer_daily_sleep_inputs(
    day_df: pd.DataFrame,
    slot_minutes: float = SLOT_MINUTES,
) -> dict[str, float]:
    wake_h = infer_wake_from_sleep_excluded(day_df, slot_minutes)
    if wake_h is None:
        morning = day_df[
            (day_df["Status"] == STATUS_LOGGED) & day_df["EnergyLevel"].notna()
        ].sort_values("hour")
        wake_h = float(morning.iloc[0]["hour"]) if not morning.empty else 7.5
    return {
        "wake_hour": float(wake_h),
        "sleep_duration": infer_sleep_duration_from_day(day_df, slot_minutes),
        "efficiency": 0.9,
    }


def build_empirical_daily_table(df: pd.DataFrame) -> pd.DataFrame:
    """One row per calendar date with sleep inputs inferred from the alertness log."""
    rows: list[dict[str, Any]] = []
    for date, day in df.groupby("date"):
        day = day.sort_values("DateTime")
        inputs = infer_daily_sleep_inputs(day)
        rows.append({"date": str(date), **inputs})
    if not rows:
        return pd.DataFrame(columns=["date", "wake_hour", "sleep_duration", "efficiency"])
    return pd.DataFrame(rows)


def build_inferred_sleep_episodes(
    raw: pd.DataFrame,
    slot_minutes: float = SLOT_MINUTES,
) -> list[Any]:
    """
    Build main-sleep episodes from consecutive SLEEP_EXCLUDED log slots.
    Returns sleep_dynamics.SleepEpisode objects.
    """
    from sleep_dynamics import SleepEpisode

    episodes: list[SleepEpisode] = []
    for _, day in raw.groupby("date"):
        day = day.sort_values("DateTime")
        block_start: datetime | None = None
        block_end: datetime | None = None
        date_str = str(day.iloc[0]["date"])
        for _, row in day.iterrows():
            if row["Status"] != STATUS_SLEEP:
                if block_start is not None and block_end is not None:
                    dur = (block_end - block_start).total_seconds() / 3600.0 + slot_minutes / 60.0
                    episodes.append(
                        SleepEpisode(
                            start=block_start,
                            end=block_end + timedelta(minutes=slot_minutes),
                            is_main=True,
                            duration_hours=dur,
                            efficiency=0.9,
                            date=date_str,
                        )
                    )
                    block_start = block_end = None
                continue
            point_dt = row["DateTime"]
            if not isinstance(point_dt, datetime):
                point_dt = pd.Timestamp(point_dt).to_pydatetime()
            if block_start is None:
                block_start = point_dt
            block_end = point_dt
        if block_start is not None and block_end is not None:
            dur = (block_end - block_start).total_seconds() / 3600.0 + slot_minutes / 60.0
            episodes.append(
                SleepEpisode(
                    start=block_start,
                    end=block_end + timedelta(minutes=slot_minutes),
                    is_main=True,
                    duration_hours=dur,
                    efficiency=0.9,
                    date=date_str,
                )
            )
    return episodes


def fitbit_empirical_overlap(
    raw: pd.DataFrame,
    sleep_logs: list[dict],
) -> dict[str, Any]:
    """Report whether Fitbit main-sleep dates overlap empirical LOGGED days."""
    emp_dates = set(raw.loc[raw["Status"] == STATUS_LOGGED, "date"].unique())
    fb_dates = {s.get("dateOfSleep") for s in sleep_logs if s.get("isMainSleep")}
    overlap = sorted(emp_dates & fb_dates)
    emp_only = sorted(emp_dates - fb_dates)
    return {
        "empirical_days": sorted(emp_dates),
        "fitbit_main_days": sorted(fb_dates),
        "overlap": overlap,
        "empirical_without_fitbit": emp_only,
        "has_gap": len(emp_only) > 0,
    }


def resolve_daily_sleep_row(
    date: str,
    day_df: pd.DataFrame,
    fitbit_by_date: pd.DataFrame | None,
) -> dict[str, float]:
    """Prefer Fitbit row for date; fall back to empirical log inference."""
    if fitbit_by_date is not None and date in fitbit_by_date.index:
        fb = fitbit_by_date.loc[date]
        if isinstance(fb, pd.DataFrame):
            fb = fb.iloc[0]
        return {
            "wake_hour": float(fb["wake_hour"]),
            "sleep_duration": float(fb["sleep_duration"]),
            "efficiency": float(fb["efficiency"]),
            "source": "fitbit",
        }
    inferred = infer_daily_sleep_inputs(day_df)
    inferred["source"] = "empirical_log"
    return inferred


def merge_fitbit_and_inferred_episodes(
    sleep_logs: list[dict],
    inferred: list[Any],
) -> list[Any]:
    """Prefer Fitbit main-sleep per date; fill gaps with log-inferred episodes."""
    from sleep_dynamics import SleepEpisode, build_sleep_episodes, merge_sleep_episodes

    fb = build_sleep_episodes(sleep_logs)
    fb_main_dates = {e.date for e in fb if e.is_main}
    supplemental = [e for e in inferred if isinstance(e, SleepEpisode) and e.date not in fb_main_dates]
    return merge_sleep_episodes(fb, supplemental)


def _parse_iso_datetime(iso_str: str) -> datetime:
    cleaned = iso_str.replace("Z", "")
    if "." in cleaned:
        cleaned = cleaned.split(".")[0]
    return datetime.fromisoformat(cleaned)


def build_wake_episodes(sleep_logs: list[dict]) -> pd.DataFrame:
    """Main-sleep wake events with full datetime (for wake-episode alignment)."""
    rows: list[dict[str, Any]] = []
    for log in sleep_logs:
        if not log.get("isMainSleep", False):
            continue
        date = log.get("dateOfSleep")
        end = log.get("endTime")
        if not date or not end:
            continue
        wake_dt = _parse_iso_datetime(end)
        start = log.get("startTime")
        sleep_dt = _parse_iso_datetime(start) if start else wake_dt
        duration = log.get("minutesAsleep", 0) / 60.0
        bed = log.get("timeInBed", 0)
        eff = (log.get("minutesAsleep", 0) / bed) if bed > 0 else 0.92
        rows.append({
            "date": date,
            "wake_datetime": wake_dt,
            "sleep_datetime": sleep_dt,
            "wake_hour": wake_dt.hour + wake_dt.minute / 60.0,
            "sleep_duration": duration,
            "efficiency": eff,
        })
    if not rows:
        return pd.DataFrame(columns=[
            "date", "wake_datetime", "sleep_datetime", "wake_hour",
            "sleep_duration", "efficiency",
        ])
    return pd.DataFrame(rows).sort_values("wake_datetime").reset_index(drop=True)


def wake_context_for_datetime(
    point_dt: datetime,
    wake_episodes: pd.DataFrame,
    sleep_logs: list[dict],
    sleep_need_hours: float,
    include_naps: bool = True,
    excluded_dates: list[str] | None = None,
) -> dict[str, Any] | None:
    """Assign sleep inputs from the most recent main wake before point_dt."""
    if wake_episodes.empty:
        return None
    valid = wake_episodes[wake_episodes["wake_datetime"] <= point_dt]
    if valid.empty:
        ep = wake_episodes.iloc[0]
        t_hours = max(0.0, (point_dt - ep["wake_datetime"]).total_seconds() / 3600.0)
    else:
        ep = valid.iloc[-1]
        t_hours = (point_dt - ep["wake_datetime"]).total_seconds() / 3600.0
    from analyze_energy_fit import debt_as_of_date

    debt = debt_as_of_date(
        sleep_logs,
        str(ep["date"]),
        sleep_need_hours,
        include_naps,
        excluded_dates=excluded_dates,
    )
    t_model = t_hours % 24.0
    return {
        "wake_episode_date": str(ep["date"]),
        "wake_datetime": ep["wake_datetime"],
        "wake_hour": float(ep["wake_hour"]),
        "sleep_duration": float(ep["sleep_duration"]),
        "sleep_efficiency": float(ep["efficiency"]),
        "sleep_debt_hours": debt,
        "t": t_model,
        "t_raw": t_hours,
        "pre_wake_on_calendar_date": False,
    }
