#!/usr/bin/env python3
"""
Empirical energy-curve fit analysis.

Loads Alertness_Master_Log.csv, merges per-day Fitbit sleep inputs, compares
candidate models M1–M6 with leave-one-day-out cross-validation, and writes
analysis/energy_fit_report.json plus comparison plots.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
from datetime import datetime, timedelta
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy.optimize import least_squares

from energy_logic import compute_sleep_debt, two_process_energy
from energy_models import (
    DEFAULT_PARAM_BOUNDS,
    MODEL_REGISTRY,
    ModelParams,
    metrics,
    pack_m2_params,
    pack_m3_params,
    pack_m4_params,
    pack_m6_params,
    predict_m1_baseline,
    predict_m2_tuned,
    predict_m3_scaled_s,
    predict_m4_circadian_baseline,
    predict_m5_fourier,
    predict_m6_time_varying_debt,
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CSV = os.path.join(REPO_ROOT, "Alertness_Master_Log.csv")
DEFAULT_REPORT = os.path.join(REPO_ROOT, "analysis", "energy_fit_report.json")
DEFAULT_PLOT = os.path.join(REPO_ROOT, "analysis", "energy_fit_plots.png")
DEFAULT_PERSONAL = os.path.join(REPO_ROOT, "python", "personal_energy_fit.json")
DEFAULT_DAILY_COMPARISON_DIR = os.path.join(REPO_ROOT, "analysis", "daily_comparison")
DEFAULT_OUTLIER_REPORT_JSON = os.path.join(REPO_ROOT, "analysis", "m4_outlier_report.json")
DEFAULT_OUTLIER_REPORT_MD = os.path.join(REPO_ROOT, "analysis", "m4_outlier_report.md")
DEFAULT_OUTLIER_DIAGNOSTICS = os.path.join(REPO_ROOT, "analysis", "m4_residual_diagnostics.png")
DEFAULT_DATA_REVIEW = os.path.join(REPO_ROOT, "analysis", "data_review_report.json")
DEFAULT_VALIDATION_REPORT = os.path.join(REPO_ROOT, "analysis", "model_fit_validation_report.md")
PRODUCTION_MODEL = "M4_circadian_daily_baseline"
OUTLIER_ERROR_THRESHOLD = 12.0
WAKE_VARIATION_WINDOW_H = 2.5

STATUS_LOGGED = "LOGGED"
STATUS_MISSED = "MISSED"
STATUS_SLEEP = "SLEEP_EXCLUDED"
STATUS_INTERP = "INTERPOLATED"


# ---------------------------------------------------------------------------
# Fitbit daily table
# ---------------------------------------------------------------------------

def _parse_iso_hour(iso_str: str) -> float:
    cleaned = iso_str.replace("Z", "")
    if "." in cleaned:
        cleaned = cleaned.split(".")[0]
    dt = datetime.fromisoformat(cleaned)
    return dt.hour + dt.minute / 60.0 + dt.second / 3600.0


def load_sleep_logs() -> list[dict]:
    candidates = [
        os.path.join(REPO_ROOT, "python", "fitbit_sleep_logs.json"),
        os.path.join(REPO_ROOT, "fitbit_summary.json"),
        os.path.join(REPO_ROOT, "python", "fitbit_summary.json"),
    ]
    for path in candidates:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if "sleep_logs" in data:
            return data["sleep_logs"]
        if "inputs" in data and "raw_sleep_logs" in data["inputs"]:
            return data["inputs"]["raw_sleep_logs"]
    return []


def build_fitbit_daily_table(
    sleep_logs: list[dict],
    sleep_need_hours: float = 8.0,
    include_naps: bool = True,
) -> pd.DataFrame:
    """One row per dateOfSleep with wake_hour, duration, efficiency."""
    if not sleep_logs:
        return pd.DataFrame(
            columns=["date", "wake_hour", "sleep_hour", "sleep_duration", "efficiency"]
        )

    by_date: dict[str, list[dict]] = {}
    for log in sleep_logs:
        date = log.get("dateOfSleep")
        if not date:
            continue
        if not include_naps and not log.get("isMainSleep", False):
            continue
        by_date.setdefault(date, []).append(log)

    rows = []
    for date, sessions in sorted(by_date.items()):
        main = next((s for s in sessions if s.get("isMainSleep")), sessions[0])
        wake_h = _parse_iso_hour(main["endTime"])
        sleep_h = _parse_iso_hour(main["startTime"])
        duration = sum(s.get("minutesAsleep", 0) for s in sessions) / 60.0
        if not include_naps:
            duration = main.get("minutesAsleep", 0) / 60.0
        bed = main.get("timeInBed", main.get("timeIn_bed", 0))
        eff = (main.get("minutesAsleep", 0) / bed) if bed > 0 else 0.92
        rows.append(
            {
                "date": date,
                "wake_hour": wake_h,
                "sleep_hour": sleep_h,
                "sleep_duration": duration,
                "efficiency": eff,
            }
        )
    return pd.DataFrame(rows)


def debt_as_of_date(
    sleep_logs: list[dict],
    target_date: str,
    sleep_need_hours: float,
    include_naps: bool = True,
    excluded_dates: list[str] | None = None,
) -> float:
    """Weighted 14-night debt using only logs on or before target_date."""
    excluded = set(excluded_dates or [])
    filtered = [
        s for s in sleep_logs if s.get("dateOfSleep", "") <= target_date
    ]
    if not filtered:
        return 0.0
    daily_sleep: dict[str, float] = {}
    for log in filtered:
        date = log.get("dateOfSleep")
        if not date or (not include_naps and not log.get("isMainSleep", False)):
            continue
        daily_sleep[date] = daily_sleep.get(date, 0.0) + log.get("minutesAsleep", 0) / 60.0

    anchor = datetime.strptime(target_date, "%Y-%m-%d")
    weighted = 0.0
    decay = 0.9
    for i in range(15):
        d = (anchor - timedelta(days=i)).strftime("%Y-%m-%d")
        if d in excluded:
            continue
        actual = daily_sleep.get(d, 0.0)
        weighted += (sleep_need_hours - actual) * (decay ** i)
    return weighted


def infer_wake_from_sleep_excluded(day_df: pd.DataFrame) -> float | None:
    from empirical_sleep import infer_wake_from_sleep_excluded as _infer_wake

    return _infer_wake(day_df)


# ---------------------------------------------------------------------------
# Empirical log loading & interpolation
# ---------------------------------------------------------------------------

def load_empirical_csv(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path)
    df["DateTime"] = pd.to_datetime(df["DateTime"])
    df["hour"] = df["DateTime"].dt.hour + df["DateTime"].dt.minute / 60.0
    df["date"] = df["DateTime"].dt.date.astype(str)
    if "EnergyLevel" in df.columns:
        df["EnergyLevel"] = pd.to_numeric(df["EnergyLevel"], errors="coerce")
    return df.sort_values("DateTime").reset_index(drop=True)


def interpolate_gaps(df: pd.DataFrame) -> pd.DataFrame:
    """
    Linearly fill MISSED slots between LOGGED points in the same wake period.
    Never crosses SLEEP_EXCLUDED blocks.
    """
    out = df.copy()
    extra_rows: list[dict] = []

    for date, day in df.groupby("date"):
        day = day.sort_values("DateTime")
        logged_idx = day.index[day["Status"] == STATUS_LOGGED].tolist()
        for i in range(len(logged_idx) - 1):
            a_idx, b_idx = logged_idx[i], logged_idx[i + 1]
            a, b = out.loc[a_idx], out.loc[b_idx]
            if pd.isna(a["EnergyLevel"]) or pd.isna(b["EnergyLevel"]):
                continue
            between = out.loc[a_idx + 1 : b_idx - 1]
            if between.empty:
                continue
            if (between["Status"] == STATUS_SLEEP).any():
                continue
            n_gap = len(between)
            # Only infer isolated one- or two-slot gaps. Longer missing runs
            # are too weakly constrained to represent empirical ratings.
            if n_gap > 2:
                continue
            for j, (idx, row) in enumerate(between.iterrows(), start=1):
                if row["Status"] != STATUS_MISSED:
                    continue
                frac = j / (n_gap + 1)
                interp_val = float(a["EnergyLevel"] + frac * (b["EnergyLevel"] - a["EnergyLevel"]))
                extra_rows.append(
                    {
                        "Timestamp": row["Timestamp"],
                        "DateTime": row["DateTime"],
                        "EnergyLevel": round(interp_val, 1),
                        "Status": STATUS_INTERP,
                        "hour": row["hour"],
                        "date": date,
                        "interpolated": True,
                    }
                )
    if extra_rows:
        interp_df = pd.DataFrame(extra_rows)
        out = pd.concat([out, interp_df], ignore_index=True)
        out = out.sort_values("DateTime").reset_index(drop=True)
    out["interpolated"] = out.get("interpolated", False)
    if "interpolated" not in out.columns:
        out["interpolated"] = False
    return out


def build_fitting_frame(
    df: pd.DataFrame,
    fitbit_daily: pd.DataFrame,
    sleep_logs: list[dict],
    sleep_need_hours: float = 8.0,
    use_interpolation: bool = False,
    excluded_dates: list[str] | None = None,
    grouping: str = "wake_episode",
) -> pd.DataFrame:
    """
    Build per-point fitting rows with sleep context.

    grouping:
      - ``wake_episode`` (default): assign sleep inputs from the most recent main
        wake before each log timestamp (fixes pre-midnight logs before calendar wake).
      - ``calendar``: one sleep row per calendar date (legacy comparison mode).
    """
    if use_interpolation:
        df = interpolate_gaps(df)

    from empirical_sleep import (
        build_wake_episodes,
        load_excluded_dates,
        load_excluded_log_datetimes,
        resolve_daily_sleep_row,
        wake_context_for_datetime,
    )

    if excluded_dates is None:
        excluded_dates = load_excluded_dates()
    excluded_logs = load_excluded_log_datetimes()

    fitbit_by_date = fitbit_daily.set_index("date") if not fitbit_daily.empty else None
    calendar_cache: dict[str, dict[str, Any]] = {}
    records: list[dict] = []

    def _calendar_daily(date: str, day: pd.DataFrame) -> dict[str, Any]:
        if date not in calendar_cache:
            calendar_cache[date] = resolve_daily_sleep_row(date, day, fitbit_by_date)
        return calendar_cache[date]

    if grouping == "calendar":
        for date, day in df.groupby("date"):
            day = day.sort_values("DateTime")
            daily = _calendar_daily(str(date), day)
            wake_h = float(daily["wake_hour"])
            sleep_dur = float(daily["sleep_duration"])
            efficiency = float(daily["efficiency"])
            debt = (
                debt_as_of_date(
                    sleep_logs,
                    date,
                    sleep_need_hours,
                    excluded_dates=excluded_dates,
                )
                if sleep_logs
                else 0.0
            )
            for _, row in day.iterrows():
                if row["Status"] not in (STATUS_LOGGED, STATUS_INTERP):
                    continue
                if pd.isna(row["EnergyLevel"]):
                    continue
                dt_str = str(row["DateTime"])
                if dt_str in excluded_logs:
                    continue
                hour = float(row["hour"])
                records.append(
                    {
                        "date": date,
                        "hour": hour,
                        "t": (hour - wake_h) % 24.0,
                        "y": float(row["EnergyLevel"]),
                        "wake_hour": wake_h,
                        "sleep_duration": sleep_dur,
                        "sleep_debt_hours": debt,
                        "sleep_efficiency": efficiency,
                        "sleep_source": daily.get("source", "unknown"),
                        "wake_episode_date": str(date),
                        "status": row["Status"],
                        "interpolated": bool(row.get("interpolated", False)),
                        "datetime": dt_str,
                        "sleep_grouping": grouping,
                        "pre_wake_on_calendar_date": hour < wake_h,
                    }
                )
        return pd.DataFrame(records)

    wake_eps = build_wake_episodes(sleep_logs)
    days_by_date = {
        str(date): day.sort_values("DateTime")
        for date, day in df.groupby("date")
    }

    for _, row in df.sort_values("DateTime").iterrows():
        if row["Status"] not in (STATUS_LOGGED, STATUS_INTERP):
            continue
        if pd.isna(row["EnergyLevel"]):
            continue
        dt_str = str(row["DateTime"])
        if dt_str in excluded_logs:
            continue
        cal_date = str(row["date"])
        day = days_by_date.get(cal_date, pd.DataFrame())
        cal_daily = _calendar_daily(cal_date, day)
        hour = float(row["hour"])
        point_dt = row["DateTime"]
        if not isinstance(point_dt, datetime):
            point_dt = pd.Timestamp(point_dt).to_pydatetime()

        ctx = None
        if not wake_eps.empty:
            ctx = wake_context_for_datetime(
                point_dt,
                wake_eps,
                sleep_logs,
                sleep_need_hours,
                include_naps=True,
                excluded_dates=excluded_dates,
            )

        if ctx is None:
            wake_h = float(cal_daily["wake_hour"])
            sleep_dur = float(cal_daily["sleep_duration"])
            efficiency = float(cal_daily["efficiency"])
            debt = (
                debt_as_of_date(
                    sleep_logs,
                    cal_date,
                    sleep_need_hours,
                    excluded_dates=excluded_dates,
                )
                if sleep_logs
                else 0.0
            )
            t = (hour - wake_h) % 24.0
            wake_episode_date = cal_date
        else:
            wake_h = float(ctx["wake_hour"])
            sleep_dur = float(ctx["sleep_duration"])
            efficiency = float(ctx["sleep_efficiency"])
            debt = float(ctx["sleep_debt_hours"])
            t = float(ctx["t"])
            wake_episode_date = ctx["wake_episode_date"]

        ep_daily = _calendar_daily(wake_episode_date, days_by_date.get(wake_episode_date, day))
        records.append(
            {
                "date": cal_date,
                "hour": hour,
                "t": t,
                "y": float(row["EnergyLevel"]),
                "wake_hour": wake_h,
                "sleep_duration": sleep_dur,
                "sleep_debt_hours": debt,
                "sleep_efficiency": efficiency,
                "sleep_source": ep_daily.get("source", cal_daily.get("source", "unknown")),
                "wake_episode_date": wake_episode_date,
                "status": row["Status"],
                "interpolated": bool(row.get("interpolated", False)),
                "datetime": dt_str,
                "sleep_grouping": grouping,
                "pre_wake_on_calendar_date": False,
            }
        )
    return pd.DataFrame(records)


# ---------------------------------------------------------------------------
# Model fitting helpers
# ---------------------------------------------------------------------------

def _row_predict_m2(row: pd.Series, params: ModelParams) -> float:
    return predict_m2_tuned(
        row["t"], row["sleep_duration"], row["sleep_debt_hours"], params=params
    )


def _fit_m2(train: pd.DataFrame) -> ModelParams:
    lb, ub = DEFAULT_PARAM_BOUNDS["M2"]

    def residual(x: np.ndarray) -> np.ndarray:
        p = pack_m2_params(x)
        pred = train.apply(lambda r: _row_predict_m2(r, p), axis=1).values
        return pred - train["y"].values

    x0 = np.array([23.0, 4.0, 1.5, 10.0, 0.55, -0.15, 1.10, 1.0])
    res = least_squares(residual, x0, bounds=(lb, ub), max_nfev=8000)
    return pack_m2_params(res.x)


def _fit_m3(train: pd.DataFrame) -> ModelParams:
    lb, ub = DEFAULT_PARAM_BOUNDS["M3"]

    def residual(x: np.ndarray) -> np.ndarray:
        p = pack_m3_params(x)
        pred = train.apply(
            lambda r: predict_m3_scaled_s(
                r["t"], r["sleep_duration"], r["sleep_debt_hours"], params=p
            ),
            axis=1,
        ).values
        return pred - train["y"].values

    x0 = np.array([23.0, 4.0, 1.5, 10.0, 0.55, -0.15, 1.10, 1.0, 0.7])
    res = least_squares(residual, x0, bounds=(lb, ub), max_nfev=8000)
    return pack_m3_params(res.x)


def _fit_m4(train: pd.DataFrame) -> ModelParams:
    lb, ub = DEFAULT_PARAM_BOUNDS["M4"]

    def residual(x: np.ndarray) -> np.ndarray:
        p = pack_m4_params(x)
        pred = train.apply(
            lambda r: predict_m4_circadian_baseline(
                r["t"],
                r["sleep_duration"],
                r["sleep_debt_hours"],
                r["sleep_efficiency"],
                params=p,
            ),
            axis=1,
        ).values
        return pred - train["y"].values

    x0 = np.array([50.0, 15.0, 8.0, 5.0, 2.0, -1.5, 10.0])
    res = least_squares(residual, x0, bounds=(lb, ub), max_nfev=8000)
    return pack_m4_params(res.x)


def _fit_m6(train: pd.DataFrame) -> ModelParams:
    lb, ub = DEFAULT_PARAM_BOUNDS["M6"]

    def residual(x: np.ndarray) -> np.ndarray:
        p = pack_m6_params(x)
        pred = train.apply(
            lambda r: predict_m6_time_varying_debt(
                r["t"], r["sleep_duration"], r["sleep_debt_hours"], params=p
            ),
            axis=1,
        ).values
        return pred - train["y"].values

    x0 = np.array([23.0, 4.0, 1.5, 10.0, 0.55, -0.15, 1.10, 1.0, 0.3])
    res = least_squares(residual, x0, bounds=(lb, ub), max_nfev=8000)
    return pack_m6_params(res.x)


def _fit_m5(train: pd.DataFrame) -> tuple[ModelParams, dict[str, float]]:
    """Fourier shape + per-day intercept regressed on sleep features."""
    days = sorted(train["date"].unique())
    day_beta: dict[str, float] = {}
    for d in days:
        sub = train[train["date"] == d]
        day_beta[d] = float(sub["y"].mean())

    # Regress day_beta on sleep features
    X_rows, y_rows, day_list = [], [], []
    for d in days:
        sub = train[train["date"] == d].iloc[0]
        X_rows.append([1.0, sub["sleep_duration"], sub["sleep_debt_hours"], sub["sleep_efficiency"]])
        y_rows.append(day_beta[d])
        day_list.append(d)
    X = np.array(X_rows)
    y = np.array(y_rows)
    beta_coef, _, _, _ = np.linalg.lstsq(X, y, rcond=None)

    # Fit Fourier on de-meaned t
    t_vals = train["t"].values
    y_dm = train["y"].values - np.array([day_beta[d] for d in train["date"]])
    omega = 2.0 * np.pi / 24.0
    Xf = np.column_stack(
        [
            np.ones(len(train)),
            np.cos(omega * t_vals),
            np.sin(omega * t_vals),
            np.cos(2 * omega * t_vals),
            np.sin(2 * omega * t_vals),
        ]
    )
    fcoef, _, _, _ = np.linalg.lstsq(Xf, y_dm, rcond=None)

    p = ModelParams(fourier_coefs=[float(c) for c in fcoef])
    sleep_reg = {
        "intercept": float(beta_coef[0]),
        "sleep_duration": float(beta_coef[1]),
        "sleep_debt_hours": float(beta_coef[2]),
        "sleep_efficiency": float(beta_coef[3]),
    }
    return p, sleep_reg


def predict_with_model(
    model_id: str,
    row: pd.Series,
    params: ModelParams | None = None,
    day_beta: float = 0.0,
    sleep_reg: dict[str, float] | None = None,
) -> float:
    if model_id == "M1_baseline":
        return predict_m1_baseline(
            row["t"], row["sleep_duration"], row["sleep_debt_hours"]
        )
    if model_id == "M2_tuned_two_process":
        return predict_m2_tuned(
            row["t"], row["sleep_duration"], row["sleep_debt_hours"], params=params  # type: ignore
        )
    if model_id == "M3_scaled_process_s":
        return predict_m3_scaled_s(
            row["t"], row["sleep_duration"], row["sleep_debt_hours"], params=params  # type: ignore
        )
    if model_id == "M4_circadian_daily_baseline":
        return predict_m4_circadian_baseline(
            row["t"],
            row["sleep_duration"],
            row["sleep_debt_hours"],
            row["sleep_efficiency"],
            params=params,
        )
    if model_id == "M5_mixed_fourier":
        if sleep_reg:
            day_beta = (
                sleep_reg["intercept"]
                + sleep_reg["sleep_duration"] * row["sleep_duration"]
                + sleep_reg["sleep_debt_hours"] * row["sleep_debt_hours"]
                + sleep_reg["sleep_efficiency"] * row["sleep_efficiency"]
            )
        return predict_m5_fourier(row["t"], day_beta=day_beta, params=params)
    if model_id == "M6_time_varying_debt":
        return predict_m6_time_varying_debt(
            row["t"], row["sleep_duration"], row["sleep_debt_hours"], params=params  # type: ignore
        )
    raise ValueError(f"Unknown model: {model_id}")


def leave_one_day_out_cv(data: pd.DataFrame) -> dict[str, Any]:
    days = sorted(data["date"].unique())
    model_ids = list(MODEL_REGISTRY.keys())
    cv_results: dict[str, Any] = {}
    all_preds: dict[str, list[float]] = {m: [] for m in model_ids}
    all_true: list[float] = []

    for holdout in days:
        train = data[data["date"] != holdout]
        test = data[data["date"] == holdout]
        if train.empty or test.empty:
            continue

        # M1 — no fit
        for _, row in test.iterrows():
            all_preds["M1_baseline"].append(
                predict_with_model("M1_baseline", row)
            )

        p2 = _fit_m2(train)
        p3 = _fit_m3(train)
        p4 = _fit_m4(train)
        p6 = _fit_m6(train)
        p5, sleep_reg = _fit_m5(train)

        for _, row in test.iterrows():
            all_preds["M2_tuned_two_process"].append(
                predict_with_model("M2_tuned_two_process", row, params=p2)
            )
            all_preds["M3_scaled_process_s"].append(
                predict_with_model("M3_scaled_process_s", row, params=p3)
            )
            all_preds["M4_circadian_daily_baseline"].append(
                predict_with_model("M4_circadian_daily_baseline", row, params=p4)
            )
            all_preds["M5_mixed_fourier"].append(
                predict_with_model("M5_mixed_fourier", row, params=p5, sleep_reg=sleep_reg)
            )
            all_preds["M6_time_varying_debt"].append(
                predict_with_model("M6_time_varying_debt", row, params=p6)
            )
            all_true.append(float(row["y"]))

    y_true = np.array(all_true)
    for mid in model_ids:
        y_pred = np.array(all_preds[mid][: len(y_true)])
        cv_results[mid] = metrics(y_true, y_pred)

    winner = min(cv_results, key=lambda k: cv_results[k]["mae"])
    cv_results["_winner"] = winner
    return cv_results


def fit_full_models(data: pd.DataFrame) -> dict[str, Any]:
    """Fit each tunable model on all data for export."""
    out: dict[str, Any] = {}
    out["M2_tuned_two_process"] = _fit_m2(data).to_dict()
    out["M3_scaled_process_s"] = _fit_m3(data).to_dict()
    out["M4_circadian_daily_baseline"] = _fit_m4(data).to_dict()
    out["M6_time_varying_debt"] = _fit_m6(data).to_dict()
    p5, sleep_reg = _fit_m5(data)
    out["M5_mixed_fourier"] = {"params": p5.to_dict(), "sleep_regression": sleep_reg}
    return out


def predict_legacy_two_process(
    t: float,
    sleep_duration: float,
    sleep_debt_hours: float,
) -> float:
    """Pre-M4 production two-process (get_energy_level fallback, peak_offset=13)."""
    return two_process_energy(
        t,
        sleep_debt_hours,
        sleep_duration,
        circadian_peak_offset=13.0,
        clamp=False,
    ) * 100.0


def _format_clock_hour(h: float) -> str:
    h = h % 24.0
    hh = int(h)
    mm = int(round((h - hh) * 60.0)) % 60
    return f"{hh:02d}:{mm:02d}"


def _load_m4_params(
    data: pd.DataFrame,
    personal_path: str = DEFAULT_PERSONAL,
    report_path: str = DEFAULT_REPORT,
    full_fit: dict[str, Any] | None = None,
) -> ModelParams:
    if full_fit and "M4_circadian_daily_baseline" in full_fit:
        p4 = ModelParams.from_dict(full_fit["M4_circadian_daily_baseline"])
    elif os.path.exists(personal_path):
        with open(personal_path, "r", encoding="utf-8") as f:
            personal = json.load(f)
        params = personal.get("params", personal)
        p4 = ModelParams.from_dict(params)
    elif os.path.exists(report_path):
        with open(report_path, "r", encoding="utf-8") as f:
            report = json.load(f)
        p4 = ModelParams.from_dict(
            report["full_fit_params"]["M4_circadian_daily_baseline"]
        )
    else:
        return _fit_m4(data)

    # Production overrides (e.g. tuned k_s) live in personal_energy_fit.json
    if os.path.exists(personal_path):
        with open(personal_path, "r", encoding="utf-8") as f:
            personal = json.load(f)
        overrides = personal.get("params", personal)
        for key in ("k_s", "tau_wake", "tau_sleep", "tau_inertia", "debt_factor"):
            if key in overrides:
                setattr(p4, key, overrides[key])
    return p4


def _split_at_clock_wrap(x_vals: list[float], y_vals: list[float]) -> list[tuple[list[float], list[float]]]:
    """Split a curve when clock-hour x wraps past midnight (avoid spurious diagonals)."""
    if not x_vals:
        return []
    segments: list[tuple[list[float], list[float]]] = [([], [])]
    segments[0][0].append(x_vals[0])
    segments[0][1].append(y_vals[0])
    for i in range(1, len(x_vals)):
        if x_vals[i] - x_vals[i - 1] < -6.0:
            segments.append(([], []))
        segments[-1][0].append(x_vals[i])
        segments[-1][1].append(y_vals[i])
    return segments


def _plot_clock_segments(
    ax: Any,
    x_vals: list[float],
    y_vals: list[float],
    **plot_kwargs: Any,
) -> None:
    label = plot_kwargs.pop("label", None)
    for i, (xs, ys) in enumerate(_split_at_clock_wrap(x_vals, y_vals)):
        ax.plot(xs, ys, label=label if i == 0 else "_nolegend_", **plot_kwargs)


# ---------------------------------------------------------------------------
# M4 outlier / residual analysis
# ---------------------------------------------------------------------------

def compute_m4_residual_frame(
    data: pd.DataFrame,
    raw: pd.DataFrame,
    p4: ModelParams,
    *,
    sleep_logs: list[dict] | None = None,
    sleep_need_hours: float = 8.0,
    use_sleep_dynamics: bool = True,
) -> pd.DataFrame:
    """Per LOGGED point: M4 prediction, residual, and context for outlier review."""
    logged = data[data["status"] == STATUS_LOGGED].copy()
    if logged.empty:
        return pd.DataFrame()

    if "DateTime" in raw.columns:
        dt_lookup = (
            raw[raw["Status"] == STATUS_LOGGED]
            .assign(hour_key=lambda d: d["hour"].round(6))
            .drop_duplicates(subset=["date", "hour_key"], keep="first")
            .set_index(["date", "hour_key"])["DateTime"]
        )
    else:
        dt_lookup = pd.Series(dtype="datetime64[ns]")

    rows: list[dict[str, Any]] = []
    for _, row in logged.iterrows():
        hour_key = round(float(row["hour"]), 6)
        dt = dt_lookup.get((row["date"], hour_key))
        m4_pred = _m4_pred_at_point(
            row,
            p4,
            dt,
            sleep_logs=sleep_logs,
            sleep_need_hours=sleep_need_hours,
            raw=raw,
            use_sleep_dynamics=use_sleep_dynamics,
        )
        residual = float(row["y"] - m4_pred)
        wake_h = float(row["wake_hour"])
        hour = float(row["hour"])
        pre_wake = row.get("pre_wake_on_calendar_date")
        if pd.isna(pre_wake):
            pre_wake = hour < wake_h
        rows.append({
            "datetime": dt.isoformat(sep=" ") if dt is not None and not pd.isna(dt) else "",
            "date": row["date"],
            "hour": hour,
            "t": float(row["t"]),
            "y": float(row["y"]),
            "m4_pred": float(m4_pred),
            "residual": residual,
            "abs_error": abs(residual),
            "wake_hour": wake_h,
            "sleep_duration": float(row["sleep_duration"]),
            "sleep_debt_hours": float(row["sleep_debt_hours"]),
            "sleep_efficiency": float(row["sleep_efficiency"]),
            "pre_wake_on_calendar_date": bool(pre_wake),
            "sleep_grouping": row.get("sleep_grouping", "calendar"),
        })

    frame = pd.DataFrame(rows)
    if frame.empty:
        return frame

    frame["_dt"] = pd.to_datetime(frame["datetime"], errors="coerce")
    logged_raw = raw[raw["Status"] == STATUS_LOGGED].copy()
    if "DateTime" in logged_raw.columns:
        logged_raw["_log_dt"] = pd.to_datetime(logged_raw["DateTime"], errors="coerce")
        logged_raw = logged_raw.sort_values("_log_dt")
    else:
        logged_raw["_log_dt"] = pd.NaT

    frame["neighbor_delta"] = 0.0
    frame["gap_before_mins"] = 999.0
    frame["near_sleep"] = False
    frame["gap_spans_sleep"] = False
    frame["post_wake_window"] = False
    frame["neighbor_residuals"] = None

    for date, day in frame.groupby("date"):
        day = day.sort_values("_dt")
        indices = day.index.tolist()
        ys = day["y"].values
        residuals = day["residual"].values
        dts = day["_dt"].values
        ts = day["t"].values

        for i, idx in enumerate(indices):
            nb_y: list[float] = []
            nb_r: list[float] = []
            if i > 0:
                nb_y.append(float(ys[i - 1]))
                nb_r.append(float(residuals[i - 1]))
            if i < len(indices) - 1:
                nb_y.append(float(ys[i + 1]))
                nb_r.append(float(residuals[i + 1]))
            if i > 1:
                nb_y.append(float(ys[i - 2]))
                nb_r.append(float(residuals[i - 2]))
            if i < len(indices) - 2:
                nb_y.append(float(ys[i + 2]))
                nb_r.append(float(residuals[i + 2]))

            y_val = float(frame.loc[idx, "y"])
            med = float(np.median(nb_y)) if nb_y else y_val
            frame.at[idx, "neighbor_delta"] = abs(y_val - med)
            frame.at[idx, "neighbor_median_y"] = med
            frame.at[idx, "neighbor_residuals"] = nb_r

            prev_dt = pd.Timestamp(dts[i - 1]) if i > 0 and not pd.isna(dts[i - 1]) else None
            curr_dt = pd.Timestamp(dts[i]) if not pd.isna(dts[i]) else None
            if curr_dt is None or pd.isna(curr_dt):
                frame.at[idx, "gap_before_mins"] = 999.0
                gap_sleep = False
            elif prev_dt is not None and not pd.isna(prev_dt):
                frame.at[idx, "gap_before_mins"] = (
                    curr_dt - prev_dt
                ).total_seconds() / 60.0
                gap_sleep = _gap_spans_sleep(raw, prev_dt, curr_dt)
            else:
                earlier = logged_raw[logged_raw["_log_dt"] < curr_dt]
                if earlier.empty:
                    frame.at[idx, "gap_before_mins"] = 999.0
                    gap_sleep = False
                else:
                    prev_dt = pd.Timestamp(earlier["_log_dt"].iloc[-1])
                    frame.at[idx, "gap_before_mins"] = (
                        curr_dt - prev_dt
                    ).total_seconds() / 60.0
                    gap_sleep = _gap_spans_sleep(raw, prev_dt, curr_dt)
            frame.at[idx, "gap_spans_sleep"] = gap_sleep
            frame.at[idx, "near_sleep"] = _is_near_sleep(
                raw, str(date), float(frame.loc[idx, "hour"])
            )
            t_since_wake = float(ts[i])
            frame.at[idx, "post_wake_window"] = (
                t_since_wake <= WAKE_VARIATION_WINDOW_H or gap_sleep
            )

    day_medians = frame.groupby("date")["residual"].median()
    frame["day_median_residual"] = frame["date"].map(day_medians)

    classifications: list[str] = []
    rationales: list[str] = []
    for _, row in frame.iterrows():
        cls, rat = _classify_outlier_row(row)
        classifications.append(cls)
        rationales.append(rat)

    frame["classification"] = classifications
    frame["rationale"] = rationales
    frame = frame.drop(columns=["_dt", "neighbor_residuals", "day_median_residual", "neighbor_median_y"], errors="ignore")
    return frame.sort_values("abs_error", ascending=False).reset_index(drop=True)


def _is_near_sleep(raw: pd.DataFrame, date: str, hour: float, window: float = 1.0) -> bool:
    day = raw[raw["date"] == date]
    if day.empty:
        return False
    flagged = day[day["Status"].isin([STATUS_SLEEP, STATUS_MISSED])]
    for _, srow in flagged.iterrows():
        if abs(float(srow["hour"]) - hour) <= window:
            return True
    return False


def _gap_spans_sleep(
    raw: pd.DataFrame,
    prev_dt: pd.Timestamp | None,
    curr_dt: pd.Timestamp | None,
) -> bool:
    """True when SLEEP_EXCLUDED slots lie strictly between two LOGGED timestamps."""
    if prev_dt is None or curr_dt is None or pd.isna(prev_dt) or pd.isna(curr_dt):
        return False
    if curr_dt <= prev_dt:
        return False
    if "DateTime" not in raw.columns:
        return False
    times = pd.to_datetime(raw["DateTime"], errors="coerce")
    between = raw[(times > prev_dt) & (times < curr_dt)]
    return bool(between["Status"].eq(STATUS_SLEEP).any())


def _m4_pred_at_point(
    row: pd.Series,
    p4: ModelParams,
    dt: Any,
    *,
    sleep_logs: list[dict] | None = None,
    sleep_need_hours: float = 8.0,
    raw: pd.DataFrame | None = None,
    use_sleep_dynamics: bool = True,
) -> float:
    """M4 prediction with optional sleep-dynamics adjustment (Process S + inertia)."""
    if use_sleep_dynamics and sleep_logs and dt is not None and not pd.isna(dt):
        from sleep_dynamics import predict_energy_sleep_aware

        point_dt = pd.Timestamp(dt).floor("s").to_pydatetime()
        result = predict_energy_sleep_aware(
            point_dt,
            sleep_logs,
            sleep_need_hours,
            params=p4,
            raw_df=raw,
        )
        if result.get("energy") is not None:
            return float(result["energy"])
    return float(
        predict_m4_circadian_baseline(
            row["t"],
            row["sleep_duration"],
            row["sleep_debt_hours"],
            row["sleep_efficiency"],
            params=p4,
        )
    )


def _classify_outlier_row(row: pd.Series) -> tuple[str, str]:
    abs_err = float(row["abs_error"])
    residual = float(row["residual"])
    neighbor_delta = float(row["neighbor_delta"])
    neighbor_median_y = float(row.get("neighbor_median_y", row["y"]))
    gap_before = float(row["gap_before_mins"])
    near_sleep = bool(row["near_sleep"])
    gap_spans_sleep = bool(row.get("gap_spans_sleep", False))
    post_wake = bool(row.get("post_wake_window", False))
    t_since_wake = float(row.get("t", 0.0))
    nb_residuals: list[float] = row.get("neighbor_residuals", []) or []
    day_median = float(row.get("day_median_residual", 0.0))

    if (
        row.get("sleep_grouping", "calendar") != "wake_episode"
        and row.get("pre_wake_on_calendar_date", False)
        and abs_err >= OUTLIER_ERROR_THRESHOLD
    ):
        return (
            "alignment_artifact",
            "Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.",
        )

    if abs_err >= OUTLIER_ERROR_THRESHOLD:
        if gap_spans_sleep or (post_wake and t_since_wake <= WAKE_VARIATION_WINDOW_H):
            return (
                "wake_variation",
                "Expected post-sleep or wake-boundary variation (gap was sleep or t<"
                f"{WAKE_VARIATION_WINDOW_H:.1f}h since wake).",
            )
        if near_sleep and t_since_wake <= WAKE_VARIATION_WINDOW_H:
            return (
                "wake_variation",
                "Sharp change near wake boundary; subjective discontinuity expected.",
            )
        if gap_spans_sleep and gap_before >= 90:
            return (
                "wake_variation",
                f"Logging gap ({gap_before:.0f} min) spans sleep; not a recall-quality issue.",
            )
        if neighbor_delta >= 15:
            if float(row["y"]) >= 75 and neighbor_median_y >= 65:
                return (
                    "model_limit",
                    "Sustained high-energy cluster; M4 under-predicts afternoon peak.",
                )
            return (
                "data_suspect",
                f"Isolated spike/dip vs neighbors (delta={neighbor_delta:.0f}).",
            )
        if gap_before >= 90 and not gap_spans_sleep:
            return (
                "data_suspect",
                f"Large awake logging gap before entry ({gap_before:.0f} min); recall may be inconsistent.",
            )
        if near_sleep and neighbor_delta >= 10:
            return (
                "data_suspect",
                "Near sleep/missed boundary with sharp local change.",
            )

        same_sign = sum(
            1 for r in nb_residuals
            if (r > 0) == (residual > 0) and abs(r) >= 5
        )
        if same_sign >= 3:
            sub = "phase_mismatch"
            if abs(day_median) >= 8 and abs(residual - day_median) < 6:
                sub = "baseline_miss"
            return (
                "model_limit",
                f"Local systematic bias ({sub}); {same_sign} neighbors share residual sign.",
            )

    if abs_err >= OUTLIER_ERROR_THRESHOLD:
        return ("ambiguous", "Large error without a confident dirty-data or model signal.")

    return ("within_tolerance", "Error below outlier threshold.")


def _write_residual_diagnostics(frame: pd.DataFrame, plot_path: str) -> None:
    os.makedirs(os.path.dirname(plot_path), exist_ok=True)
    fig, axes = plt.subplots(1, 2, figsize=(12, 4))
    ax1, ax2 = axes

    ax1.hist(frame["residual"], bins=25, color="steelblue", edgecolor="white")
    ax1.axvline(0, color="black", linestyle="--", alpha=0.5)
    ax1.set_xlabel("Observed − M4 predicted")
    ax1.set_ylabel("Count")
    ax1.set_title("M4 residual distribution")

    colors = {
        "alignment_artifact": "crimson",
        "wake_variation": "mediumseagreen",
        "data_suspect": "darkorange",
        "model_limit": "steelblue",
        "ambiguous": "gray",
        "within_tolerance": "lightgray",
    }
    for cls, grp in frame.groupby("classification"):
        ax2.scatter(
            grp["t"], grp["residual"],
            alpha=0.6, s=25, label=cls, c=colors.get(cls, "gray"),
        )
    ax2.axhline(0, color="black", linestyle="--", alpha=0.5)
    ax2.set_xlabel("Hours since wake")
    ax2.set_ylabel("Residual")
    ax2.set_title("Residual vs hours since wake")
    ax2.legend(fontsize=7, loc="best")
    ax2.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(plot_path, dpi=120)
    plt.close()


def _write_outlier_markdown(
    report: dict[str, Any],
    md_path: str,
) -> None:
    lines = [
        "# M4 Outlier Deviation Report",
        "",
        f"Generated: {report['generated_at']}",
        f"Points analyzed: {report['n_logged']} LOGGED over {report['n_days']} days",
        f"Outlier threshold: |error| ≥ {report['threshold']}",
        "",
        "## Classification summary",
        "",
    ]
    for cls, count in sorted(report["classification_counts"].items(), key=lambda x: -x[1]):
        lines.append(f"- **{cls}**: {count}")
    lines.extend(["", "## Top outliers", ""])
    lines.append(
        "| DateTime | Obs | Pred | Error | Class | Rationale |"
    )
    lines.append("|---|---:|---:|---:|---|---|")
    for o in report["top_outliers"]:
        lines.append(
            f"| {o['datetime']} | {o['y']:.0f} | {o['m4_pred']:.1f} | "
            f"{o['residual']:+.1f} | {o['classification']} | {o['rationale']} |"
        )
    lines.extend(["", "## Assessment", "", report["assessment"], ""])
    os.makedirs(os.path.dirname(md_path), exist_ok=True)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def _build_outlier_assessment(
    frame: pd.DataFrame,
    top: pd.DataFrame,
    classification_counts: dict[str, int],
) -> str:
    n_align = classification_counts.get("alignment_artifact", 0)
    n_data = classification_counts.get("data_suspect", 0)
    n_wake = classification_counts.get("wake_variation", 0)
    n_model = classification_counts.get("model_limit", 0)
    n_amb = classification_counts.get("ambiguous", 0)
    large = frame[frame["abs_error"] >= OUTLIER_ERROR_THRESHOLD]

    parts = [
        f"Of {len(large)} points with |error| ≥ {OUTLIER_ERROR_THRESHOLD:.0f}, "
        f"{n_align} look like **wake-alignment artifacts** (pre-wake logs using wrong night's Fitbit row), "
        f"{n_wake} are **expected wake/sleep-gap variation**, "
        f"{n_data} look **data-suspect** (isolated spikes or post-gap inconsistencies), "
        f"{n_model} reflect **model limits** (systematic local bias), and {n_amb} are **ambiguous**.",
    ]
    if n_align > 0:
        parts.append(
            "Alignment issues are pipeline bugs, not dirty empirical ratings — fix by grouping "
            "points into wake episodes instead of calendar dates."
        )
    if n_model > 0:
        parts.append(
            "Model-limit errors cluster where M4's fixed circadian phase cannot match your "
            "day-specific peak timing; expect this until phase can vary by day or more data is logged."
        )
    if n_data > 0:
        parts.append(
            "Data-suspect points should be manually reviewed in the log; exclude only if you "
            "confirm a mis-tap or inconsistent scale use."
        )
    if not large.empty:
        worst = top.iloc[0]
        parts.append(
            f"Largest miss: {worst['datetime']} observed {worst['y']:.0f} vs M4 {worst['m4_pred']:.1f} "
            f"({worst['classification']}) — {worst['rationale']}"
        )
    return " ".join(parts)


def analyze_m4_outliers(
    data: pd.DataFrame,
    raw: pd.DataFrame,
    *,
    full_fit: dict[str, Any] | None = None,
    personal_path: str = DEFAULT_PERSONAL,
    report_path: str = DEFAULT_REPORT,
    top_n: int = 25,
    json_path: str = DEFAULT_OUTLIER_REPORT_JSON,
    md_path: str = DEFAULT_OUTLIER_REPORT_MD,
    diagnostics_path: str = DEFAULT_OUTLIER_DIAGNOSTICS,
    sleep_logs: list[dict] | None = None,
    sleep_need_hours: float = 8.0,
) -> dict[str, Any]:
    p4 = _load_m4_params(data, personal_path, report_path, full_fit)
    frame = compute_m4_residual_frame(
        data, raw, p4,
        sleep_logs=sleep_logs,
        sleep_need_hours=sleep_need_hours,
    )
    if frame.empty:
        raise RuntimeError("No LOGGED points for outlier analysis")

    large = frame[frame["abs_error"] >= OUTLIER_ERROR_THRESHOLD]
    classification_counts = (
        large["classification"].value_counts().to_dict()
        if not large.empty else {}
    )
    top = frame.head(top_n)

    assessment = _build_outlier_assessment(frame, top, classification_counts)

    report: dict[str, Any] = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "n_logged": int(len(frame)),
        "n_days": int(frame["date"].nunique()),
        "threshold": OUTLIER_ERROR_THRESHOLD,
        "n_above_threshold": int(len(large)),
        "classification_counts": classification_counts,
        "assessment": assessment,
        "top_outliers": top.to_dict(orient="records"),
    }

    os.makedirs(os.path.dirname(json_path), exist_ok=True)
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    _write_outlier_markdown(report, md_path)
    _write_residual_diagnostics(frame, diagnostics_path)

    return report


def run_outlier_report(
    csv_path: str = DEFAULT_CSV,
    report_path: str = DEFAULT_REPORT,
    personal_path: str = DEFAULT_PERSONAL,
    use_interpolation: bool = False,
    top_n: int = 25,
    json_path: str = DEFAULT_OUTLIER_REPORT_JSON,
    md_path: str = DEFAULT_OUTLIER_REPORT_MD,
    diagnostics_path: str = DEFAULT_OUTLIER_DIAGNOSTICS,
    daily_plots_dir: str = DEFAULT_DAILY_COMPARISON_DIR,
    regenerate_daily_plots: bool = True,
    grouping: str = "wake_episode",
) -> dict[str, Any]:
    sleep_logs = load_sleep_logs()
    sleep_need = 8.0
    if sleep_logs:
        main_logs = [s for s in sleep_logs if s.get("isMainSleep")]
        if main_logs:
            effs = []
            for s in main_logs:
                bed = s.get("timeInBed", 0)
                asleep = s.get("minutesAsleep", 0)
                effs.append(asleep / bed if bed > 0 else 0.92)
            sleep_need = 8.58 * (sum(effs) / len(effs))

    fitbit_daily = build_fitbit_daily_table(sleep_logs)
    raw = load_empirical_csv(csv_path)
    data = build_fitting_frame(
        raw,
        fitbit_daily,
        sleep_logs,
        sleep_need_hours=sleep_need,
        use_interpolation=use_interpolation,
        grouping=grouping,
    )
    if len(data) < 1:
        raise RuntimeError("No LOGGED points for outlier analysis")

    full_fit = None
    if os.path.exists(report_path):
        with open(report_path, "r", encoding="utf-8") as f:
            full_fit = json.load(f).get("full_fit_params")

    p4 = _load_m4_params(data, personal_path, report_path, full_fit)
    residual_frame = compute_m4_residual_frame(
        data, raw, p4,
        sleep_logs=sleep_logs,
        sleep_need_hours=sleep_need,
    )
    report = analyze_m4_outliers(
        data, raw, full_fit=full_fit, personal_path=personal_path,
        report_path=report_path, top_n=top_n,
        json_path=json_path, md_path=md_path, diagnostics_path=diagnostics_path,
        sleep_logs=sleep_logs, sleep_need_hours=sleep_need,
    )

    if regenerate_daily_plots:
        make_daily_comparison_plots(
            data, raw, full_fit=full_fit, personal_path=personal_path,
            report_path=report_path, output_dir=daily_plots_dir,
            residual_frame=residual_frame,
            sleep_logs=sleep_logs, sleep_need_hours=sleep_need,
        )

    print("\n" + "=" * 60)
    print("M4 OUTLIER ANALYSIS")
    print("=" * 60)
    print(f"Points above threshold ({OUTLIER_ERROR_THRESHOLD:.0f}): {report['n_above_threshold']}")
    for cls, count in sorted(report["classification_counts"].items(), key=lambda x: -x[1]):
        print(f"  {cls}: {count}")
    print(f"\nTop {min(top_n, len(report['top_outliers']))} outliers:")
    print(f"{'DateTime':<20} {'Obs':>4} {'Pred':>6} {'Err':>7} {'Class':<20}")
    for o in report["top_outliers"][:top_n]:
        print(
            f"{o['datetime']:<20} {o['y']:>4.0f} {o['m4_pred']:>6.1f} "
            f"{o['residual']:>+7.1f} {o['classification']:<20}"
        )
    print(f"\nJSON: {json_path}")
    print(f"Markdown: {md_path}")
    print(f"Diagnostics: {diagnostics_path}")
    return report


def make_daily_comparison_plots(
    data: pd.DataFrame,
    raw: pd.DataFrame,
    *,
    full_fit: dict[str, Any] | None = None,
    personal_path: str = DEFAULT_PERSONAL,
    report_path: str = DEFAULT_REPORT,
    output_dir: str = DEFAULT_DAILY_COMPARISON_DIR,
    residual_frame: pd.DataFrame | None = None,
    sleep_logs: list[dict] | None = None,
    sleep_need_hours: float = 8.0,
) -> list[dict[str, Any]]:
    """
    One PNG per day: empirical LOGGED points vs legacy two-process vs fitted M4.
    X-axis is clock time; models use per-day sleep inputs from the fitting frame.
    """
    os.makedirs(output_dir, exist_ok=True)
    if sleep_logs is None:
        sleep_logs = load_sleep_logs()
    p4 = _load_m4_params(data, personal_path, report_path, full_fit)
    if residual_frame is None:
        residual_frame = compute_m4_residual_frame(
            data, raw, p4,
            sleep_logs=sleep_logs,
            sleep_need_hours=sleep_need_hours,
        )
    summaries: list[dict[str, Any]] = []
    png_names: list[str] = []

    logged = data[data["status"] == STATUS_LOGGED].copy()

    for date in sorted(logged["date"].unique()):
        day = logged[logged["date"] == date].sort_values("hour")
        if day.empty:
            continue

        wake_h = float(day.iloc[0]["wake_hour"])
        sleep_dur = float(day.iloc[0]["sleep_duration"])
        debt = float(day.iloc[0]["sleep_debt_hours"])
        eff = float(day.iloc[0]["sleep_efficiency"])

        t_min = float(day["t"].min())
        t_max = float(day["t"].max())
        t_grid = np.arange(t_min, t_max + 0.01, 0.25)

        legacy_y = [
            predict_legacy_two_process(t, sleep_dur, debt) for t in t_grid
        ]
        first = day.iloc[0]
        if "datetime" in first and first.get("datetime"):
            wake_ts = pd.to_datetime(first["datetime"]) - pd.Timedelta(hours=float(first["t"]))
        else:
            wake_ts = None
        m4_y = []
        for t in t_grid:
            stub = pd.Series({
                "t": t,
                "sleep_duration": sleep_dur,
                "sleep_debt_hours": debt,
                "sleep_efficiency": eff,
            })
            pt_dt = wake_ts + pd.Timedelta(hours=t) if wake_ts is not None else None
            m4_y.append(
                _m4_pred_at_point(
                    stub, p4, pt_dt,
                    sleep_logs=sleep_logs,
                    sleep_need_hours=sleep_need_hours,
                    raw=raw,
                )
            )
        model_x = [(wake_h + t) % 24.0 for t in t_grid]

        legacy_at_pts = day.apply(
            lambda r: predict_legacy_two_process(
                r["t"], r["sleep_duration"], r["sleep_debt_hours"]
            ),
            axis=1,
        )
        m4_at_pts = day.apply(
            lambda r: _m4_pred_at_point(
                r, p4,
                pd.to_datetime(r["datetime"]) if "datetime" in r.index and r.get("datetime") else None,
                sleep_logs=sleep_logs,
                sleep_need_hours=sleep_need_hours,
                raw=raw,
            ),
            axis=1,
        )
        mae_legacy = float(np.mean(np.abs(day["y"] - legacy_at_pts)))
        mae_m4 = float(np.mean(np.abs(day["y"] - m4_at_pts)))

        fig, ax = plt.subplots(figsize=(10, 5))

        raw_day = raw[raw["date"] == date] if "date" in raw.columns else pd.DataFrame()
        if not raw_day.empty:
            sleep_rows = raw_day[raw_day["Status"] == STATUS_SLEEP]
            for _, srow in sleep_rows.iterrows():
                sh = float(srow["hour"])
                ax.axvspan(sh - 0.25, sh + 0.25, color="gray", alpha=0.15, zorder=0)

        emp_x = day["hour"].values
        emp_y = day["y"].values
        _plot_clock_segments(
            ax, list(emp_x), list(emp_y),
            marker="o", linestyle="-", color="black", markersize=5, linewidth=1,
            label="Empirical (LOGGED)", zorder=3,
        )
        _plot_clock_segments(
            ax, model_x, legacy_y,
            linestyle="--", color="darkorange", linewidth=2,
            label="Two-process (pre-M4)", zorder=2,
        )
        _plot_clock_segments(
            ax, model_x, m4_y,
            linestyle="-", color="steelblue", linewidth=2,
            label="M4 personal fit", zorder=2,
        )

        day_outliers = residual_frame[residual_frame["date"] == date].nlargest(3, "abs_error")
        if not day_outliers.empty:
            ax.scatter(
                day_outliers["hour"], day_outliers["y"],
                c="red", s=90, zorder=6, edgecolors="darkred", linewidths=1,
                label="Top M4 outliers",
            )

        x_lo = min(float(emp_x.min()), min(model_x)) - 0.5
        x_hi = max(float(emp_x.max()), max(model_x)) + 0.5
        ax.set_xlim(x_lo, x_hi)
        ax.set_ylim(0, 100)
        ax.set_ylabel("Energy (0–100)")
        ax.set_xlabel("Clock time")
        ax.xaxis.set_major_formatter(
            plt.FuncFormatter(lambda x, _pos: _format_clock_hour(x))
        )
        ax.set_title(f"{date} — Empirical vs two-process vs M4")
        ax.legend(loc="best", fontsize=9)
        ax.grid(True, alpha=0.3)

        subtitle = (
            f"n={len(day)} logged | wake {_format_clock_hour(wake_h)} | "
            f"MAE two-process={mae_legacy:.1f}, M4={mae_m4:.1f}"
        )
        ax.text(0.5, 1.02, subtitle, transform=ax.transAxes, ha="center", fontsize=9)
        fig.text(
            0.5, 0.01,
            "M4 uses sleep-dynamics-adjusted predictions (Process S + inertia) when Fitbit is available.",
            ha="center", fontsize=8, color="gray",
        )

        png_name = f"{date}.png"
        png_path = os.path.join(output_dir, png_name)
        plt.tight_layout(rect=[0, 0.03, 1, 0.95])
        fig.savefig(png_path, dpi=120)
        plt.close(fig)

        summaries.append({
            "date": date,
            "n_logged": int(len(day)),
            "wake_hour": wake_h,
            "mae_two_process": mae_legacy,
            "mae_m4": mae_m4,
            "png": png_path,
        })
        png_names.append(png_name)

    if png_names:
        html_path = os.path.join(output_dir, "index.html")
        items = "\n".join(
            f'  <div><h2>{n.replace(".png", "")}</h2>'
            f'<img src="{n}" alt="{n}" style="max-width:900px;width:100%"></div>'
            for n in png_names
        )
        html = (
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            "<title>Daily energy comparison</title>"
            "<style>body{font-family:sans-serif;max-width:960px;margin:2em auto}"
            "div{margin-bottom:2em;border-bottom:1px solid #ddd;padding-bottom:1em}</style>"
            "</head><body><h1>Daily energy: empirical vs two-process vs M4</h1>\n"
            f"{items}\n</body></html>"
        )
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(html)

    return summaries


def _day_validation_note(date: str, mae_m4: float, mae_by_date: dict[str, float]) -> str:
    if not mae_by_date:
        return ""
    best_mae = min(mae_by_date.values())
    worst_mae = max(mae_by_date.values())
    if mae_m4 >= worst_mae - 0.05:
        if date == "2026-07-20":
            return "Worst day; midnight pre-wake LOGGED cluster (alignment artifacts)"
        return "Worst day in sample"
    if mae_m4 <= best_mae + 0.05:
        return "Best fit in sample"
    if mae_m4 < 15.0:
        return "Strong day-level fit"
    if mae_m4 > 20.0:
        return "Elevated error; check phase alignment"
    return ""


def write_validation_report(
    fit_report: dict[str, Any],
    data_review: dict[str, Any],
    outlier_report: dict[str, Any],
    daily_summaries: list[dict[str, Any]],
    *,
    output_path: str = DEFAULT_VALIDATION_REPORT,
    daily_plots_dir: str = DEFAULT_DAILY_COMPARISON_DIR,
) -> str:
    """Aggregate existing analysis artifacts into a user-facing validation report."""
    m4_cv = fit_report["cv_metrics"][PRODUCTION_MODEL]
    m4_in = fit_report["in_sample_metrics"][PRODUCTION_MODEL]
    cv_winner = fit_report.get("cv_winner", "")
    overlap = fit_report.get("fitbit_empirical_overlap", {})
    sleep_sources = fit_report.get("daily_sleep_sources", {})
    date_range = fit_report.get("date_range", ["?", "?"])
    mae_by_date = {s["date"]: s["mae_m4"] for s in daily_summaries}

    ranked_models = sorted(
        fit_report["cv_metrics"].items(),
        key=lambda item: item[1]["mae"],
    )

    lines = [
        "# Model Fit Validation Report",
        "",
        f"Generated: {datetime.now().isoformat(timespec='seconds')}",
        f"Empirical log: {fit_report.get('csv_path', DEFAULT_CSV)}",
        f"Analysis report: {fit_report.get('generated_at', 'unknown')}",
        "",
        "## Executive summary",
        "",
        f"- **Sample:** {fit_report.get('n_logged', 0)} LOGGED points over "
        f"{fit_report.get('n_days', 0)} days ({date_range[0]} to {date_range[1]})",
        f"- **Overlap:** {len(overlap.get('overlap', []))} days with both empirical and Fitbit sleep",
        f"- **Sleep gaps:** {'none' if not overlap.get('has_gap') else 'present'}",
        f"- **Excluded dates:** {fit_report.get('excluded_dates', []) or 'none'}",
        f"- **Sleep grouping:** {fit_report.get('sleep_grouping', 'wake_episode')}",
        f"- **Production model:** {PRODUCTION_MODEL} (`personal_energy_fit.json`)",
        f"- **LOO CV winner:** {cv_winner} (M4 kept for interpretability)",
        "",
        "Abbreviations (MAE, LOO, R², M1–M6, Process S, k_s, outlier classes): "
        "[energy_model_glossary.md](../docs/energy_model_glossary.md).",
        "",
        "## M4 accuracy",
        "",
        "| Metric | In-sample | Leave-one-day-out |",
        "|---|---:|---:|",
        f"| MAE | {m4_in['mae']:.1f} | {m4_cv['mae']:.1f} |",
        f"| RMSE | {m4_in['rmse']:.1f} | {m4_cv['rmse']:.1f} |",
        f"| R² | {m4_in['r2']:.3f} | {m4_cv['r2']:.3f} |",
        f"| Bias | {m4_in['bias']:.2f} | {m4_cv['bias']:.2f} |",
        "",
        "### Grouping comparison",
        "",
        f"- **Calendar-date MAE:** {data_review.get('mae_m4_calendar_grouping', 0):.1f}",
        f"- **Wake-episode MAE:** {data_review.get('mae_m4_wake_episode_grouping', 0):.1f}",
        "",
        "Fit harness defaults to **wake-episode** grouping; calendar-date MAE is kept "
        "for regression comparison. Wake-episode grouping fixes pre-wake midnight logs "
        "that previously used the wrong night's sleep inputs.",
        "",
        "## Model comparison (LOO CV)",
        "",
        "| Rank | Model | LOO MAE | LOO R² |",
        "|---:|---|---:|---:|",
    ]
    for rank, (mid, m) in enumerate(ranked_models, start=1):
        marker = "**" if mid == PRODUCTION_MODEL else ""
        end = "**" if mid == PRODUCTION_MODEL else ""
        winner_note = " (CV winner)" if mid == cv_winner else ""
        lines.append(
            f"| {rank} | {marker}{mid}{end}{winner_note} | {m['mae']:.1f} | {m['r2']:.3f} |"
        )

    lines.extend([
        "",
        f"Production stays on **{PRODUCTION_MODEL}** unless LOO improves by ≥2 MAE points "
        "with acceptable interpretability.",
        "",
        "## Per-day breakdown",
        "",
        "| Date | n | Sleep source | M4 MAE | Legacy 2-proc MAE | Notes |",
        "|---|---:|---|---:|---:|---|",
    ])
    for s in daily_summaries:
        note = _day_validation_note(s["date"], s["mae_m4"], mae_by_date)
        lines.append(
            f"| {s['date']} | {s['n_logged']} | {sleep_sources.get(s['date'], '?')} "
            f"| {s['mae_m4']:.1f} | {s['mae_two_process']:.1f} | {note} |"
        )

    counts = outlier_report.get("classification_counts", {})
    threshold = outlier_report.get("threshold", OUTLIER_ERROR_THRESHOLD)
    n_above = outlier_report.get("n_above_threshold", 0)
    lines.extend([
        "",
        "## Outlier summary",
        "",
        f"Points with |error| ≥ {threshold:.0f}: **{n_above}** of "
        f"{outlier_report.get('n_logged', 0)}",
        "",
        "| Class | Count | Meaning |",
        "|---|---:|---|",
        f"| alignment_artifact | {counts.get('alignment_artifact', 0)} | "
        "Pre-wake logs using wrong night's Fitbit row |",
        f"| wake_variation | {counts.get('wake_variation', 0)} | "
        "Expected post-sleep / wake-boundary discontinuity |",
        f"| model_limit | {counts.get('model_limit', 0)} | "
        "Systematic local bias / fixed circadian phase |",
        f"| data_suspect | {counts.get('data_suspect', 0)} | "
        "Isolated spikes or post-gap inconsistencies |",
        f"| ambiguous | {counts.get('ambiguous', 0)} | "
        "Could not classify confidently |",
        "",
        outlier_report.get("assessment", ""),
        "",
        "## Graphs",
        "",
        "### Per-day comparison (Jul 16–22)",
        "",
    ])
    for s in daily_summaries:
        rel = os.path.join("daily_comparison", f"{s['date']}.png").replace("\\", "/")
        lines.append(f"- [{s['date']}]({rel}) — M4 MAE {s['mae_m4']:.1f}")
    lines.extend([
        "",
        f"- [Gallery (all days)](daily_comparison/index.html)",
        "- [Aggregate fit plots](energy_fit_plots.png)",
        "- [M4 residual diagnostics](m4_residual_diagnostics.png)",
        "",
        "## Interpretation",
        "",
        f"M4 in-sample MAE ~{m4_in['mae']:.0f} on {fit_report.get('n_logged', 0)} points "
        f"(wake-episode grouping). LOO MAE ~{m4_cv['mae']:.0f} and LOO R² {m4_cv['r2']:.2f} "
        "are expected to be weak on a 7-day sample — the model is not yet validated for "
        "day-ahead prediction.",
        "",
        "**Strengths:** Jul 21–22 show the tightest per-day fits. Wake-episode grouping "
        "eliminated alignment-artifact outliers from the pre-wake midnight cluster.",
        "",
        "**Limitations:** Jul 20 evening still shows the largest model_limit cluster. "
        "Fixed circadian phase cannot track day-specific peak timing.",
        "",
        "**Next steps:**",
        "1. Continue daily logging through Jul 25 (day-10 bi-weekly re-fit gate).",
        "2. Manually review data_suspect outliers before excluding any ratings.",
        "3. Re-fit M4 after Jul 25 when ≥10 overlap days are available.",
        "",
    ])

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return output_path


def run_validation_report(
    csv_path: str = DEFAULT_CSV,
    report_path: str = DEFAULT_REPORT,
    personal_path: str = DEFAULT_PERSONAL,
    data_review_path: str = DEFAULT_DATA_REVIEW,
    outlier_json_path: str = DEFAULT_OUTLIER_REPORT_JSON,
    output_path: str = DEFAULT_VALIDATION_REPORT,
    daily_plots_dir: str = DEFAULT_DAILY_COMPARISON_DIR,
    use_interpolation: bool = False,
) -> str:
    """Load analysis artifacts and write consolidated validation report."""
    for path, label in [
        (report_path, "energy fit report"),
        (data_review_path, "data review report"),
        (outlier_json_path, "outlier report"),
    ]:
        if not os.path.exists(path):
            raise RuntimeError(
                f"Missing {label} at {path}. Run analyze_energy_fit.py with "
                "--outlier-report and --data-review first."
            )

    with open(report_path, "r", encoding="utf-8") as f:
        fit_report = json.load(f)
    with open(data_review_path, "r", encoding="utf-8") as f:
        data_review = json.load(f)
    with open(outlier_json_path, "r", encoding="utf-8") as f:
        outlier_report = json.load(f)

    daily_summaries = run_daily_comparison(
        csv_path=csv_path,
        output_dir=daily_plots_dir,
        personal_path=personal_path,
        report_path=report_path,
        use_interpolation=use_interpolation,
    )

    path = write_validation_report(
        fit_report, data_review, outlier_report, daily_summaries,
        output_path=output_path, daily_plots_dir=daily_plots_dir,
    )
    print(f"\nValidation report: {path}")
    return path


def run_daily_comparison(
    csv_path: str = DEFAULT_CSV,
    output_dir: str = DEFAULT_DAILY_COMPARISON_DIR,
    personal_path: str = DEFAULT_PERSONAL,
    report_path: str = DEFAULT_REPORT,
    use_interpolation: bool = False,
    grouping: str = "wake_episode",
) -> list[dict[str, Any]]:
    """Load empirical log and write per-day comparison PNGs."""
    sleep_logs = load_sleep_logs()
    sleep_need = 8.0
    if sleep_logs:
        main_logs = [s for s in sleep_logs if s.get("isMainSleep")]
        if main_logs:
            effs = []
            for s in main_logs:
                bed = s.get("timeInBed", 0)
                asleep = s.get("minutesAsleep", 0)
                effs.append(asleep / bed if bed > 0 else 0.92)
            sleep_need = 8.58 * (sum(effs) / len(effs))

    fitbit_daily = build_fitbit_daily_table(sleep_logs)
    raw = load_empirical_csv(csv_path)
    data = build_fitting_frame(
        raw,
        fitbit_daily,
        sleep_logs,
        sleep_need_hours=sleep_need,
        use_interpolation=use_interpolation,
        grouping=grouping,
    )
    if len(data) < 1:
        raise RuntimeError("No LOGGED points found for daily comparison plots")

    full_fit = None
    if os.path.exists(report_path):
        with open(report_path, "r", encoding="utf-8") as f:
            full_fit = json.load(f).get("full_fit_params")

    summaries = make_daily_comparison_plots(
        data, raw, full_fit=full_fit, personal_path=personal_path,
        report_path=report_path, output_dir=output_dir,
        sleep_logs=sleep_logs, sleep_need_hours=sleep_need,
    )

    print("\n" + "=" * 60)
    print("DAILY ENERGY COMPARISON")
    print("=" * 60)
    print(f"{'Date':<12} {'n':>4} {'MAE 2-proc':>12} {'MAE M4':>10}")
    for s in summaries:
        print(
            f"{s['date']:<12} {s['n_logged']:>4} "
            f"{s['mae_two_process']:>12.1f} {s['mae_m4']:>10.1f}"
        )
    print(f"\nGallery: {os.path.join(output_dir, 'index.html')}")
    return summaries


def make_plots(data: pd.DataFrame, fitted: dict[str, Any], plot_path: str) -> None:
    os.makedirs(os.path.dirname(plot_path), exist_ok=True)

    # Empirical profile by hours-since-wake
    bins = np.arange(0, 17, 1)
    emp_mean, emp_std, emp_n = [], [], []
    for b in bins:
        sub = data[(data["t"] >= b - 0.25) & (data["t"] < b + 0.75)]
        emp_mean.append(sub["y"].mean() if len(sub) else np.nan)
        emp_std.append(sub["y"].std() if len(sub) > 1 else 0.0)
        emp_n.append(len(sub))

    t_grid = np.linspace(0, 16, 97)
    med = data.groupby("date").first()
    ref_dur = float(data["sleep_duration"].median())
    ref_debt = float(data["sleep_debt_hours"].median())
    ref_eff = float(data["sleep_efficiency"].median())

    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    ax = axes[0, 0]
    ax.errorbar(
        bins, emp_mean, yerr=emp_std, fmt="o-", capsize=3, label="Empirical mean±std", color="black"
    )

    p2 = ModelParams.from_dict(fitted["M2_tuned_two_process"])
    p3 = ModelParams.from_dict(fitted["M3_scaled_process_s"])
    p4 = ModelParams.from_dict(fitted["M4_circadian_daily_baseline"])

    ax.plot(
        t_grid,
        [predict_m1_baseline(t, ref_dur, ref_debt) for t in t_grid],
        "--",
        label="M1 baseline",
        alpha=0.7,
    )
    ax.plot(
        t_grid,
        [predict_m2_tuned(t, ref_dur, ref_debt, params=p2) for t in t_grid],
        label="M2 tuned",
    )
    ax.plot(
        t_grid,
        [predict_m3_scaled_s(t, ref_dur, ref_debt, params=p3) for t in t_grid],
        label="M3 scaled S",
    )
    ax.plot(
        t_grid,
        [predict_m4_circadian_baseline(t, ref_dur, ref_debt, ref_eff, params=p4) for t in t_grid],
        label="M4 circ+baseline",
    )
    ax.set_xlabel("Hours since wake")
    ax.set_ylabel("Energy (0–100)")
    ax.set_title("Empirical vs model profiles")
    ax.legend(fontsize=8)
    ax.grid(True, alpha=0.3)

    # Day means
    ax2 = axes[0, 1]
    day_means = data.groupby("date")["y"].mean()
    ax2.bar(range(len(day_means)), day_means.values, color="steelblue")
    ax2.set_xticks(range(len(day_means)))
    ax2.set_xticklabels([d[5:] for d in day_means.index], rotation=45)
    ax2.set_ylabel("Mean energy")
    ax2.set_title("Daily mean energy")

    # Residual histogram for winner params (M3)
    ax3 = axes[1, 0]
    preds = data.apply(
        lambda r: predict_m3_scaled_s(
            r["t"], r["sleep_duration"], r["sleep_debt_hours"], params=p3
        ),
        axis=1,
    )
    resid = data["y"] - preds
    ax3.hist(resid, bins=20, color="coral", edgecolor="white")
    ax3.set_title("M3 residuals (full fit)")
    ax3.set_xlabel("Observed − predicted")

    # Scatter observed vs M3 predicted
    ax4 = axes[1, 1]
    ax4.scatter(preds, data["y"], alpha=0.5, s=20)
    lims = [0, 100]
    ax4.plot(lims, lims, "k--", alpha=0.5)
    ax4.set_xlabel("Predicted (M3)")
    ax4.set_ylabel("Observed")
    ax4.set_title("Observed vs predicted")

    plt.tight_layout()
    plt.savefig(plot_path, dpi=120)
    plt.close()


def run_analysis(
    csv_path: str = DEFAULT_CSV,
    report_path: str = DEFAULT_REPORT,
    plot_path: str = DEFAULT_PLOT,
    personal_path: str = DEFAULT_PERSONAL,
    use_interpolation: bool = False,
    daily_plots: bool = False,
    daily_plots_dir: str = DEFAULT_DAILY_COMPARISON_DIR,
    grouping: str = "wake_episode",
) -> dict[str, Any]:
    sleep_logs = load_sleep_logs()
    sleep_need = 8.0
    if sleep_logs:
        main_logs = [s for s in sleep_logs if s.get("isMainSleep")]
        if main_logs:
            effs = []
            for s in main_logs:
                bed = s.get("timeInBed", 0)
                asleep = s.get("minutesAsleep", 0)
                effs.append(asleep / bed if bed > 0 else 0.92)
            sleep_need = 8.58 * (sum(effs) / len(effs))

    from empirical_sleep import fitbit_empirical_overlap, load_excluded_dates

    fitbit_daily = build_fitbit_daily_table(sleep_logs)
    raw = load_empirical_csv(csv_path)
    overlap = fitbit_empirical_overlap(raw, sleep_logs)
    excluded_dates = load_excluded_dates()
    data = build_fitting_frame(
        raw,
        fitbit_daily,
        sleep_logs,
        sleep_need_hours=sleep_need,
        use_interpolation=use_interpolation,
        excluded_dates=excluded_dates,
        grouping=grouping,
    )

    if len(data) < 10:
        raise RuntimeError(f"Need at least 10 LOGGED points; got {len(data)}")

    cv = leave_one_day_out_cv(data)
    winner = cv.pop("_winner")
    full_fit = fit_full_models(data)

    # In-sample metrics for reference
    in_sample: dict[str, dict[str, float]] = {}
    p2 = ModelParams.from_dict(full_fit["M2_tuned_two_process"])
    for mid in MODEL_REGISTRY:
        preds = data.apply(lambda r: predict_with_model(mid, r, params=p2 if "M2" in mid else ModelParams.from_dict(full_fit.get(mid, {}))), axis=1)
        if mid == "M3_scaled_process_s":
            p3 = ModelParams.from_dict(full_fit["M3_scaled_process_s"])
            preds = data.apply(
                lambda r: predict_m3_scaled_s(r["t"], r["sleep_duration"], r["sleep_debt_hours"], params=p3),
                axis=1,
            )
        elif mid == "M4_circadian_daily_baseline":
            p4 = ModelParams.from_dict(full_fit["M4_circadian_daily_baseline"])
            preds = data.apply(
                lambda r: predict_m4_circadian_baseline(
                    r["t"], r["sleep_duration"], r["sleep_debt_hours"], r["sleep_efficiency"], params=p4
                ),
                axis=1,
            )
        elif mid == "M6_time_varying_debt":
            p6 = ModelParams.from_dict(full_fit["M6_time_varying_debt"])
            preds = data.apply(
                lambda r: predict_m6_time_varying_debt(r["t"], r["sleep_duration"], r["sleep_debt_hours"], params=p6),
                axis=1,
            )
        elif mid == "M5_mixed_fourier":
            p5 = ModelParams.from_dict(full_fit["M5_mixed_fourier"]["params"])
            sr = full_fit["M5_mixed_fourier"]["sleep_regression"]
            preds = data.apply(
                lambda r: predict_with_model("M5_mixed_fourier", r, params=p5, sleep_reg=sr),
                axis=1,
            )
        elif mid == "M2_tuned_two_process":
            preds = data.apply(
                lambda r: predict_m2_tuned(r["t"], r["sleep_duration"], r["sleep_debt_hours"], params=p2),
                axis=1,
            )
        elif mid == "M1_baseline":
            preds = data.apply(
                lambda r: predict_m1_baseline(r["t"], r["sleep_duration"], r["sleep_debt_hours"]),
                axis=1,
            )
        in_sample[mid] = metrics(data["y"].values, preds.values)

    report = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "csv_path": csv_path,
        "n_logged": int(len(data)),
        "n_days": int(data["date"].nunique()),
        "date_range": [data["date"].min(), data["date"].max()],
        "sleep_need_hours": sleep_need,
        "n_fitbit_days": int(len(fitbit_daily)),
        "fitbit_empirical_overlap": overlap,
        "excluded_dates": excluded_dates,
        "sleep_grouping": grouping,
        "daily_sleep_sources": (
            data.groupby("date")["sleep_source"].first().to_dict()
            if "sleep_source" in data.columns
            else {}
        ),
        "cv_winner": winner,
        "cv_metrics": cv,
        "in_sample_metrics": in_sample,
        "full_fit_params": full_fit,
        "recommendation": _build_recommendation(winner, full_fit),
    }

    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    personal = {
        "model_id": winner,
        "fitted_at": report["generated_at"],
        "cv_mae": cv[winner]["mae"],
        "params": full_fit.get(winner, full_fit.get("M3_scaled_process_s")),
    }
    if winner == "M5_mixed_fourier":
        personal["params"] = full_fit["M5_mixed_fourier"]

    with open(personal_path, "w", encoding="utf-8") as f:
        json.dump(personal, f, indent=2)

    make_plots(data, full_fit, plot_path)

    if daily_plots:
        make_daily_comparison_plots(
            data, raw, full_fit=full_fit, personal_path=personal_path,
            report_path=report_path, output_dir=daily_plots_dir,
        )

    print("\n" + "=" * 60)
    print("ENERGY FIT ANALYSIS")
    print("=" * 60)
    print(f"Points: {len(data)} over {data['date'].nunique()} days")
    print(f"CV winner: {winner} (MAE={cv[winner]['mae']:.2f})")
    print("\nLeave-one-day-out CV:")
    for mid, m in sorted(cv.items(), key=lambda x: x[1]["mae"]):
        print(f"  {mid:30s} MAE={m['mae']:5.2f}  RMSE={m['rmse']:5.2f}  R2={m['r2']:5.3f}")
    print(f"\nReport: {report_path}")
    print(f"Plot:   {plot_path}")
    print(f"Personal config: {personal_path}")
    return report


def _build_recommendation(winner: str, full_fit: dict) -> str:
    if winner == "M3_scaled_process_s":
        p = full_fit["M3_scaled_process_s"]
        return (
            f"Adopt M3 (scaled Process S) with k_S={p.get('k_s', 1):.2f}, "
            f"tau_inertia={p.get('tau_inertia', 1.5):.2f}, "
            f"amp_primary={p.get('amp_primary', 0.55):.2f}. "
            "Process S is over-weighted in the default model."
        )
    if winner == "M4_circadian_daily_baseline":
        return "Adopt M4: separate nightly baseline from intraday circadian shape."
    if winner == "M5_mixed_fourier":
        return "Adopt M5 for best CV fit; consider M3/M4 for interpretability."
    if winner == "M2_tuned_two_process":
        return "Adopt M2 with tuned personal parameters (minimal structural change)."
    return f"Winner: {winner}. Review full_fit_params before applying to production."


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze empirical energy curve fit")
    parser.add_argument("--csv", default=DEFAULT_CSV)
    parser.add_argument("--report", default=DEFAULT_REPORT)
    parser.add_argument("--plot", default=DEFAULT_PLOT)
    parser.add_argument("--personal", default=DEFAULT_PERSONAL)
    parser.add_argument("--interpolate", action="store_true")
    parser.add_argument(
        "--daily-plots",
        action="store_true",
        help="Generate per-day empirical vs two-process vs M4 PNGs",
    )
    parser.add_argument(
        "--daily-plots-dir",
        default=DEFAULT_DAILY_COMPARISON_DIR,
        help="Output directory for daily comparison plots",
    )
    parser.add_argument(
        "--outlier-report",
        action="store_true",
        help="Run M4 outlier deviation analysis",
    )
    parser.add_argument(
        "--outlier-report-top",
        type=int,
        default=25,
        help="Number of top outliers to export",
    )
    parser.add_argument(
        "--data-review",
        action="store_true",
        help="Review suspicious data, gaps, naps, and predicted peaks/troughs",
    )
    parser.add_argument(
        "--validation-report",
        action="store_true",
        help="Write consolidated model fit validation report (requires prior analysis runs)",
    )
    parser.add_argument(
        "--validation-report-path",
        default=DEFAULT_VALIDATION_REPORT,
        help="Output path for --validation-report",
    )
    parser.add_argument(
        "--calendar-grouping",
        action="store_true",
        help="Use calendar-date sleep grouping instead of wake-episode (legacy comparison)",
    )
    args = parser.parse_args()
    grouping = "calendar" if args.calendar_grouping else "wake_episode"
    if args.validation_report:
        run_validation_report(
            csv_path=args.csv,
            report_path=args.report,
            personal_path=args.personal,
            output_path=args.validation_report_path,
            daily_plots_dir=args.daily_plots_dir,
            use_interpolation=args.interpolate,
        )
        return
    if args.data_review:
        from data_review import run_data_review
        run_data_review(
            csv_path=args.csv,
            report_path=args.report,
            personal_path=args.personal,
        )
        return
    if args.outlier_report:
        run_outlier_report(
            csv_path=args.csv,
            personal_path=args.personal,
            report_path=args.report,
            use_interpolation=args.interpolate,
            top_n=args.outlier_report_top,
            daily_plots_dir=args.daily_plots_dir,
            grouping=grouping,
        )
        return
    if args.daily_plots:
        run_daily_comparison(
            csv_path=args.csv,
            output_dir=args.daily_plots_dir,
            personal_path=args.personal,
            report_path=args.report,
            use_interpolation=args.interpolate,
            grouping=grouping,
        )
        return
    run_analysis(
        csv_path=args.csv,
        report_path=args.report,
        plot_path=args.plot,
        personal_path=args.personal,
        use_interpolation=args.interpolate,
        daily_plots=False,
        daily_plots_dir=args.daily_plots_dir,
        grouping=grouping,
    )


if __name__ == "__main__":
    main()
