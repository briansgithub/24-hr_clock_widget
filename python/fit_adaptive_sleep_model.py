"""Fit the ``M8_adaptive_sleep`` personal energy model from the alertness log.

``fit_empirical_clock_model.py`` fits only the M7 local-clock baseline; the
Process-S / debt / inertia corrections that M8 layers on top of that baseline
had no committed fitter, so the production parameters could not be reproduced.
This script closes that gap.

Structure
---------
M8 predicts ``baseline(clock - phase) - h*dS - d*debt_load - i*inertia_load``.
The baseline is linear in its five Fourier coefficients, and the three penalty
terms do not involve them, so the fit alternates trivially:

1. pick a candidate for the nonlinear block (tau_wake, tau_sleep, inertia_tau,
   homeostatic_scale, debt_scale, inertia_scale);
2. compute each point's penalty ``P`` from that candidate;
3. solve the baseline in closed form against ``y + P`` -- the same equal-weight
   -per-day weighted least squares ``fit_empirical_clock_model._fit`` uses.

Because no training point carries a bathyphase or a circadian peak offset, the
phase shift is zero throughout, so the design matrix is constant across
candidates. Per-day normal equations are therefore accumulated once and each
leave-one-day-out fold is a subtraction, which makes an exhaustive grid search
cheap.

Penalty inputs come from ``energy_logic``'s own helpers, so a fitted parameter
set evaluates identically here, in the widget, and in the Kotlin port.

Usage::

    python python/fit_adaptive_sleep_model.py \
        --csv Alertness_Master_Log.csv \
        --output python/personal_energy_fit.json
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import energy_logic  # noqa: E402
from analyze_energy_fit import (  # noqa: E402
    debt_as_of_date,
    load_empirical_csv,
    load_sleep_logs,
)
from empirical_sleep import (  # noqa: E402
    build_inferred_sleep_episodes,
    build_wake_episodes,
    load_excluded_log_datetimes,
)
from fit_empirical_clock_model import _design  # noqa: E402

MODEL_ID = "M8_adaptive_sleep"

STATUS_LOGGED = "LOGGED"
STATUS_MISSED = "MISSED"
STATUS_SLEEP = "SLEEP_EXCLUDED"

# Held fixed: these define the scale/definition of the state variables rather
# than being identifiable from ratings alone.
#   * reference_sleep_hours  -- the definition of a "rested" night
#   * process_s_min/max      -- Process S normalisation, degenerate with
#                               homeostatic_scale
#   * homeostatic_delta_*    -- the asymmetric clamp on the S deviation
#   * max_phase_shift_hours -- phase machinery that is inert during fitting
#     (no per-point bathyphase), so the data carries no information about it
#
# reference_peak_clock is NOT free and NOT fixed: it names the clock hour where
# the fitted baseline peaks, and the phase shift is measured relative to it. It
# is therefore derived from the solved coefficients below. Leaving it stale
# would silently bias every bathyphase-driven shift by the difference.
#   * debt_saturation_hours  -- debt is near-constant within a day, so its
#     curvature is not identifiable
FIXED_PARAMS = {
    "reference_sleep_hours": 8.0,
    "max_phase_shift_hours": 3.0,
    "debt_saturation_hours": 12.0,
    "process_s_min": 0.05,
    "process_s_max": 1.0,
    "homeostatic_delta_min": -0.15,
    "homeostatic_delta_max": 0.35,
}

# Search grid for the nonlinear block. Scales stay strictly positive so the
# monotonicity guarantees asserted by EnergyCalculatorTest and
# test_adaptive_energy_model continue to hold (more debt / less sleep must
# never raise predicted energy).
GRID = {
    "tau_wake": [8.0, 11.0, 14.0, 17.0, 20.0, 23.0, 26.0, 30.0, 36.0],
    "tau_sleep": [1.5, 2.5, 3.5, 4.5, 5.5, 7.0, 9.0],
    "inertia_tau": [0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0],
    "homeostatic_scale": [5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 45.0, 55.0],
    "debt_scale": [0.5, 1.0, 2.0, 3.0, 4.0, 6.0, 9.0],
    "inertia_scale": [2.0, 4.0, 8.0, 12.0, 16.0, 20.0, 26.0],
}

# The three correction terms are each partially degenerate with the Fourier
# baseline: a saturating evening homeostatic penalty mimics the baseline's own
# evening decline, and post-wake inertia mimics its morning rise. Measured on
# this data the entire grid is statistically indistinguishable -- the best
# candidate beats the correction-free curve by less than the standard error of
# its own LOO MAE -- so the ratings identify the baseline but not this block.
#
# Free minimisation therefore picks noise, and a one-standard-error rule
# collapses to the zero-correction corner, which would silently reduce M8 to
# the M7 clock curve and delete its response to sleep debt and duration.
# Default is to hold the block at its established production values and refit
# only what the data determines. ``--corrections fit`` runs the search anyway
# and is reported alongside as a diagnostic.
INCUMBENT_CORRECTIONS = {
    "tau_wake": 23.0,
    "tau_sleep": 4.0,
    "inertia_tau": 0.75,
    "homeostatic_scale": 25.0,
    "debt_scale": 2.0,
    "inertia_scale": 12.0,
}


# ---------------------------------------------------------------------------
# Sleep context
# ---------------------------------------------------------------------------

def synthesize_sleep_logs(df, fitbit_logs: list[dict]) -> tuple[list[dict], dict[str, str]]:
    """Union Fitbit sleep records with log-inferred episodes for missing dates.

    ``build_wake_episodes`` and ``debt_as_of_date`` both consume Fitbit-shaped
    records, so filling the gaps in that shape keeps every downstream helper
    working unchanged instead of forking a parallel code path.
    """
    covered = {
        log.get("dateOfSleep")
        for log in fitbit_logs
        if log.get("isMainSleep") and log.get("dateOfSleep")
    }
    sources = {date: "fitbit" for date in covered}

    synthetic: list[dict] = []
    for episode in build_inferred_sleep_episodes(df):
        if not episode.is_main or episode.date in covered:
            continue
        minutes = int(round(episode.duration_hours * 60.0))
        synthetic.append(
            {
                "dateOfSleep": episode.date,
                "isMainSleep": True,
                "startTime": episode.start.strftime("%Y-%m-%dT%H:%M:%S.000"),
                "endTime": episode.end.strftime("%Y-%m-%dT%H:%M:%S.000"),
                "minutesAsleep": minutes,
                "timeInBed": minutes,
                "logId": f"inferred-{episode.date}",
                "logType": "inferred_from_log",
            }
        )
        sources[episode.date] = "empirical_log"

    # One episode per date: keep the longest inferred run when a day has several.
    best: dict[str, dict] = {}
    for log in synthetic:
        date = log["dateOfSleep"]
        if date not in best or log["minutesAsleep"] > best[date]["minutesAsleep"]:
            best[date] = log
    return fitbit_logs + list(best.values()), sources


def nap_intervals_for(episode_wake: datetime, sleep_logs: list[dict]) -> tuple[tuple[float, float], ...]:
    """Naps as (start, end) hours after the given main wake, within 24h."""
    out: list[tuple[float, float]] = []
    for log in sleep_logs:
        if log.get("isMainSleep", True):
            continue
        start = log.get("startTime")
        end = log.get("endTime")
        if not start or not end:
            continue
        start_dt = datetime.fromisoformat(start.replace("Z", "").split(".")[0])
        end_dt = datetime.fromisoformat(end.replace("Z", "").split(".")[0])
        rel_start = (start_dt - episode_wake).total_seconds() / 3600.0
        rel_end = (end_dt - episode_wake).total_seconds() / 3600.0
        if rel_end <= 0.0 or rel_start >= 24.0:
            continue
        out.append((max(0.0, rel_start), min(24.0, rel_end)))
    return tuple(sorted(out))


# ---------------------------------------------------------------------------
# Training frame
# ---------------------------------------------------------------------------

def interpolate_day(day_rows: list[dict], max_gap: int) -> tuple[list[dict], int]:
    """Linear fill of interior MISSED runs no longer than ``max_gap`` slots."""
    values = [row["energy"] if row["status"] == STATUS_LOGGED else None for row in day_rows]
    original = [value is not None for value in values]
    valid = [i for i, value in enumerate(values) if value is not None]
    filled = 0

    for left, right in zip(valid[:-1], valid[1:]):
        gap = day_rows[left + 1 : right]
        if not 1 <= len(gap) <= max_gap:
            continue
        if any(row["status"] != STATUS_MISSED for row in gap):
            continue
        left_value, right_value = float(values[left]), float(values[right])
        for index in range(left + 1, right):
            fraction = (index - left) / (right - left)
            values[index] = left_value + fraction * (right_value - left_value)
            filled += 1

    out = []
    for index, row in enumerate(day_rows):
        if values[index] is None:
            continue
        out.append({**row, "energy": float(values[index]), "original": original[index]})
    return out, filled


def build_training_points(
    csv_path: Path,
    min_logged: int,
    max_gap: int,
    sleep_need_hours: float,
    verbose: bool = True,
) -> dict:
    df = load_empirical_csv(str(csv_path))
    fitbit_logs = load_sleep_logs()
    sleep_logs, sleep_sources = synthesize_sleep_logs(df, fitbit_logs)
    wake_episodes = build_wake_episodes(sleep_logs)
    excluded_slots = load_excluded_log_datetimes()

    # Nights with no sleep record at all must not read as "slept zero hours";
    # skip them in the debt window so early dates are not penalised for the
    # absence of history before logging began.
    known_nights = {log.get("dateOfSleep") for log in sleep_logs if log.get("isMainSleep")}
    span_start = min(known_nights)
    debt_excluded = sorted(
        (datetime.strptime(span_start, "%Y-%m-%d") - timedelta(days=i)).strftime("%Y-%m-%d")
        for i in range(1, 16)
    )

    by_date: defaultdict[str, list[dict]] = defaultdict(list)
    for _, row in df.iterrows():
        status = row["Status"]
        energy = row["EnergyLevel"]
        by_date[str(row["date"])].append(
            {
                "datetime": row["DateTime"].to_pydatetime(),
                "date": str(row["date"]),
                "hour": float(row["hour"]),
                "status": status,
                "energy": None if (status != STATUS_LOGGED or energy != energy) else float(energy),
            }
        )

    logged_counts = {
        date: sum(row["status"] == STATUS_LOGGED and row["energy"] is not None for row in rows)
        for date, rows in by_date.items()
    }
    included_dates = sorted(d for d, c in logged_counts.items() if c >= min_logged)
    excluded_dates = sorted(d for d, c in logged_counts.items() if c < min_logged)

    points: list[dict] = []
    interpolated_counts: dict[str, int] = {}
    dropped = {"excluded_slot": 0, "no_wake_context": 0, "stale_episode": 0, "asleep": 0}

    for date in included_dates:
        day_rows = sorted(by_date[date], key=lambda r: r["datetime"])
        filled_rows, filled = interpolate_day(day_rows, max_gap)
        interpolated_counts[date] = filled
        for row in filled_rows:
            if str(row["datetime"]) in excluded_slots:
                dropped["excluded_slot"] += 1
                continue
            valid = wake_episodes[wake_episodes["wake_datetime"] <= row["datetime"]]
            if valid.empty:
                dropped["no_wake_context"] += 1
                continue
            episode = valid.iloc[-1]
            elapsed = (row["datetime"] - episode["wake_datetime"]).total_seconds() / 3600.0
            # Guard: without this, a point past the end of the episode list
            # silently reuses a distant wake and t wraps modulo 24.
            if not 0.0 <= elapsed < 24.0:
                dropped["stale_episode"] += 1
                continue
            naps = nap_intervals_for(episode["wake_datetime"].to_pydatetime()
                                     if hasattr(episode["wake_datetime"], "to_pydatetime")
                                     else episode["wake_datetime"], sleep_logs)
            points.append(
                {
                    "date": date,
                    "datetime": row["datetime"],
                    "hour": row["hour"],
                    "energy": row["energy"],
                    "original": row["original"],
                    "t": elapsed,
                    "wake_hour": float(episode["wake_hour"]),
                    "sleep_duration": float(episode["sleep_duration"]),
                    "naps": naps,
                    "debt": debt_as_of_date(
                        sleep_logs,
                        str(episode["date"]),
                        sleep_need_hours,
                        True,
                        excluded_dates=debt_excluded,
                    ),
                    "episode_date": str(episode["date"]),
                }
            )

    if verbose:
        span = sorted(sleep_sources)
        n_fitbit = sum(1 for v in sleep_sources.values() if v == "fitbit")
        print(f"[fit] sleep context: {len(span)} night(s), {n_fitbit} from Fitbit, "
              f"{len(span) - n_fitbit} inferred from log markers")
        print(f"[fit] days: {len(included_dates)} included, {len(excluded_dates)} below "
              f"min_logged={min_logged} -> {', '.join(excluded_dates) or 'none'}")
        print(f"[fit] points dropped: {dropped}")

    return {
        "points": points,
        "included_dates": included_dates,
        "excluded_dates": excluded_dates,
        "logged_counts": logged_counts,
        "interpolated_counts": interpolated_counts,
        "sleep_sources": sleep_sources,
        "dropped": dropped,
        "debt_excluded": debt_excluded,
    }


# ---------------------------------------------------------------------------
# Fit
# ---------------------------------------------------------------------------

def penalty_components(points: list[dict], tau_wake: float, tau_sleep: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Per-point (clamped process-S delta, hours since last wake, debt load).

    Delegates to ``energy_logic`` so the fitted parameters mean exactly what
    they mean at runtime.
    """
    params = dict(FIXED_PARAMS)
    params.update({"tau_wake": tau_wake, "tau_sleep": tau_sleep})
    deltas, since_wake, asleep_flags = [], [], []
    for point in points:
        delta, hours, asleep = energy_logic._adaptive_process_s_context(
            point["t"], point["sleep_duration"], point["naps"], params
        )
        deltas.append(
            max(FIXED_PARAMS["homeostatic_delta_min"],
                min(FIXED_PARAMS["homeostatic_delta_max"], delta))
        )
        since_wake.append(hours)
        asleep_flags.append(asleep)
    return np.asarray(deltas), np.asarray(since_wake), np.asarray(asleep_flags, dtype=bool)


def fit(points: list[dict], included_dates: list[str], mode: str = "hold",
        verbose: bool = True) -> dict:
    dates = [p["date"] for p in points]
    y = np.asarray([p["energy"] for p in points], dtype=float)
    original = np.asarray([p["original"] for p in points], dtype=bool)
    debt_load = 1.0 - np.exp(
        -np.maximum(0.0, np.asarray([p["debt"] for p in points]))
        / FIXED_PARAMS["debt_saturation_hours"]
    )

    # No training point carries a bathyphase, so the phase shift is zero and the
    # design matrix is shared by every candidate.
    design = _design([p["hour"] for p in points])

    per_day_count: defaultdict[str, int] = defaultdict(int)
    for date in dates:
        per_day_count[date] += 1
    weights = np.asarray([1.0 / per_day_count[date] for date in dates])

    index_by_date = {date: np.asarray([i for i, d in enumerate(dates) if d == date])
                     for date in included_dates}
    n_points, n_days = len(points), len(included_dates)

    # Per-day normal equations. Only the right-hand side varies with a
    # candidate, so each leave-one-day-out fold is a subtraction rather than a
    # refit and the whole grid stays affordable.
    weighted_design = design * weights[:, None]
    selector = np.zeros((n_days, n_points))
    normal_by_date = np.zeros((n_days, 5, 5))
    for position, date in enumerate(included_dates):
        idx = index_by_date[date]
        selector[position, idx] = 1.0
        xd, wd = design[idx], weights[idx]
        normal_by_date[position] = xd.T @ (wd[:, None] * xd)
    normal_full = normal_by_date.sum(axis=0)
    fold_normals = normal_full[None, :, :] - normal_by_date  # (D, 5, 5), constant

    # Scoring set: original (non-interpolated) ratings, grouped by fold.
    fold_rows = [index_by_date[d][original[index_by_date[d]]] for d in included_dates]
    flat_rows = np.concatenate(fold_rows)
    fold_of_row = np.repeat(np.arange(n_days), [len(rows) for rows in fold_rows])
    design_scored, y_scored = design[flat_rows], y[flat_rows]

    # process-S depends only on the two time constants; cache per pair.
    s_cache: dict[tuple[float, float], tuple[np.ndarray, np.ndarray, np.ndarray]] = {}
    for tau_w in GRID["tau_wake"]:
        for tau_s in GRID["tau_sleep"]:
            s_cache[(tau_w, tau_s)] = penalty_components(points, tau_w, tau_s)

    def fold_errors(penalty: np.ndarray) -> np.ndarray:
        """Leave-one-day-out residuals on original ratings for a given penalty."""
        target = y + penalty
        cross_by_date = selector @ (weighted_design * target[:, None])  # (D, 5)
        rhs = cross_by_date.sum(axis=0)[None, :] - cross_by_date
        betas = np.linalg.solve(fold_normals, rhs[..., None])[..., 0]  # (D, 5)
        predicted = np.einsum("ij,ij->i", design_scored, betas[fold_of_row])
        return predicted - penalty[flat_rows] - y_scored

    def evaluate(penalty: np.ndarray) -> tuple[float, float, int]:
        err = fold_errors(penalty)
        return float(np.mean(np.abs(err))), float(np.sqrt(np.mean(err ** 2))), err.size

    def build_penalty(tau_w: float, tau_s: float, tau_i: float,
                      hom: float, debt: float, iner: float) -> np.ndarray:
        if (tau_w, tau_s) not in s_cache:  # e.g. the incumbent, off-grid
            s_cache[(tau_w, tau_s)] = penalty_components(points, tau_w, tau_s)
        delta, since_wake, _ = s_cache[(tau_w, tau_s)]
        return (hom * delta
                + debt * debt_load
                + iner * np.exp(-since_wake / max(0.001, tau_i)))

    keys = ("tau_wake", "tau_sleep", "inertia_tau",
            "homeostatic_scale", "debt_scale", "inertia_scale")
    results: list[tuple] = []
    for tau_w in GRID["tau_wake"]:
        for tau_s in GRID["tau_sleep"]:
            delta, since_wake, _ = s_cache[(tau_w, tau_s)]
            for tau_i in GRID["inertia_tau"]:
                inertia_load = np.exp(-since_wake / max(0.001, tau_i))
                for hom in GRID["homeostatic_scale"]:
                    hom_term = hom * delta
                    for debt in GRID["debt_scale"]:
                        debt_term = hom_term + debt * debt_load
                        for iner in GRID["inertia_scale"]:
                            mae, rmse, n = evaluate(debt_term + iner * inertia_load)
                            results.append(
                                (mae, rmse, n, tau_w, tau_s, tau_i, hom, debt, iner)
                            )

    maes = np.asarray([row[0] for row in results])
    best_row = results[int(np.argmin(maes))]
    strength = lambda row: float(np.std(build_penalty(*row[3:])))

    # Identifiability check: compare the best candidate's advantage against the
    # standard error of its own LOO MAE.
    best_err = fold_errors(build_penalty(*best_row[3:]))
    std_error = float(np.std(np.abs(best_err), ddof=1) / math.sqrt(best_err.size))
    within = [row for row in results if row[0] <= best_row[0] + std_error]

    # Incumbent production correction block, scored on this data.
    incumbent_values = tuple(INCUMBENT_CORRECTIONS[key] for key in keys)
    incumbent_mae, incumbent_rmse, incumbent_n = evaluate(build_penalty(*incumbent_values))

    # Baseline-only reference: the M7-style clock curve with every correction off.
    clock_mae, clock_rmse, _ = evaluate(np.zeros_like(y))

    # Two distinct questions. Does *any* correction help more than noise? And do
    # the ratings pin down *which* correction? The second is what choosing
    # values requires, and a grid that is almost entirely within one standard
    # error of its own best answers it: no.
    corrections_help = (clock_mae - best_row[0]) > std_error
    determined_fraction = len(within) / len(results)
    corrections_determined = determined_fraction < 0.05

    if mode == "fit" and corrections_determined:
        chosen = best_row
        selection = "grid search on leave-one-day-out MAE"
    else:
        chosen = (incumbent_mae, incumbent_rmse, incumbent_n) + incumbent_values
        reason = (
            f"{determined_fraction:.0%} of the grid lies within one standard error "
            f"of the best candidate, so the ratings do not pin down these values"
        )
        if mode == "fit":
            reason = "requested --corrections fit was overridden: " + reason
        selection = f"held at established production values ({reason})"
    best = dict(zip(keys, chosen[3:]))
    best.update({"cv_mae": chosen[0], "cv_rmse": chosen[1], "cv_n": chosen[2]})

    params = dict(FIXED_PARAMS)
    params.update({key: best[key] for key in keys})
    params["reference_peak_clock"] = 0.0  # replaced below, once coefs are solved

    delta, since_wake, _ = s_cache[(best["tau_wake"], best["tau_sleep"])]
    penalty = (best["homeostatic_scale"] * delta
               + best["debt_scale"] * debt_load
               + best["inertia_scale"] * np.exp(-since_wake / max(0.001, best["inertia_tau"])))
    target = y + penalty
    cross_full = (selector @ (weighted_design * target[:, None])).sum(axis=0)
    coefs = np.linalg.solve(normal_full, cross_full)
    params["reference_peak_clock"] = baseline_peak_clock([float(v) for v in coefs])

    at_bounds = [
        key for key in keys
        if best[key] in (min(GRID[key]), max(GRID[key])) and len(GRID[key]) > 1
    ]
    in_sample = design @ coefs - penalty - y
    if verbose:
        print(f"[fit] evaluated {len(results)} correction candidate(s)")
        print(f"[fit] LOO MAE  baseline-only (no corrections) = {clock_mae:.3f}")
        print(f"[fit] LOO MAE  incumbent corrections          = {incumbent_mae:.3f}")
        print(f"[fit] LOO MAE  best grid candidate            = {best_row[0]:.3f} "
              f"(correction sd {strength(best_row):.2f} pts)")
        print(f"[fit] standard error of LOO MAE = {std_error:.3f}; "
              f"{len(within)}/{len(results)} ({determined_fraction:.0%}) candidates "
              f"within one SE of best")
        print(f"[fit] a correction helps beyond noise: {corrections_help}; "
              f"the values are determined: {corrections_determined}")
        print(f"[fit] mode={mode} -> {selection}")
        print(f"[fit] selected: LOO MAE={best['cv_mae']:.3f} RMSE={best['cv_rmse']:.3f} "
              f"on n={best['cv_n']} logged points")
        print(f"[fit] in-sample MAE={np.mean(np.abs(in_sample[original])):.3f}")
        print(f"[fit] baseline peaks at {params['reference_peak_clock']:.2f}h "
              f"(phase-shift anchor derived from the fitted coefficients)")
        print("[fit] params: " + ", ".join(f"{key}={best[key]:g}" for key in keys))
        if at_bounds and mode == "fit":
            print(f"[fit] WARNING: at grid bound -> {', '.join(at_bounds)}")

    return {
        "circadian_coefs": [float(v) for v in coefs],
        "params": params,
        "corrections_mode": mode,
        "corrections_selection": selection,
        "corrections_help_beyond_noise": bool(corrections_help),
        "corrections_values_determined": bool(corrections_determined),
        "best_grid_cv_mae": float(best_row[0]),
        "incumbent_cv_mae": float(incumbent_mae),
        "cv_mae_standard_error": std_error,
        "n_within_one_se": len(within),
        "params_at_grid_bound": at_bounds,
        "cv_mae": best["cv_mae"],
        "cv_rmse": best["cv_rmse"],
        "cv_n": best["cv_n"],
        "clock_only_cv_mae": clock_mae,
        "clock_only_cv_rmse": clock_rmse,
        "in_sample_mae": float(np.mean(np.abs(in_sample[original]))),
        "n_candidates": len(results),
    }


def baseline_peak_clock(coefs: list[float], step_hours: float = 0.01) -> float:
    """Clock hour at which the fitted two-harmonic baseline peaks.

    ``adaptive_sleep_energy`` measures its phase shift relative to this hour, so
    it has to track the coefficients rather than stay at a literal.
    """
    hours = np.arange(0.0, 24.0, step_hours)
    theta = 2.0 * np.pi * hours / 24.0
    curve = (coefs[0]
             + coefs[1] * np.cos(theta) + coefs[2] * np.sin(theta)
             + coefs[3] * np.cos(2.0 * theta) + coefs[4] * np.sin(2.0 * theta))
    return float(hours[int(np.argmax(curve))])


def derive_sleep_need(sleep_logs: list[dict]) -> float:
    """Same 8.58 * mean-efficiency rule the analysis harness uses."""
    ratios = [
        log.get("minutesAsleep", 0) / log["timeInBed"]
        for log in sleep_logs
        if log.get("isMainSleep") and log.get("timeInBed")
    ]
    if not ratios:
        return 8.0
    return 8.58 * float(np.mean(ratios))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=Path("Alertness_Master_Log.csv"))
    parser.add_argument("--output", type=Path, default=Path("python/personal_energy_fit.json"))
    parser.add_argument("--min-logged", type=int, default=20)
    parser.add_argument("--max-gap", type=int, default=2)
    parser.add_argument(
        "--corrections",
        choices=("hold", "fit"),
        default="hold",
        help="hold: keep the production Process-S/debt/inertia block and refit only "
             "the baseline (default). fit: also take the grid winner, but only when "
             "it beats the correction-free curve by more than one standard error.",
    )
    args = parser.parse_args()

    sleep_need = derive_sleep_need(load_sleep_logs())
    print(f"[fit] sleep_need_hours={sleep_need:.3f}")

    frame = build_training_points(args.csv, args.min_logged, args.max_gap, sleep_need)
    points = frame["points"]
    if not points:
        print("[fit] no usable training points")
        return 1

    result = fit(points, frame["included_dates"], mode=args.corrections)

    params = dict(result["params"])
    params["circadian_coefs"] = result["circadian_coefs"]
    ordered = {
        "circadian_coefs": params["circadian_coefs"],
        "reference_sleep_hours": params["reference_sleep_hours"],
        "reference_peak_clock": params["reference_peak_clock"],
        "max_phase_shift_hours": params["max_phase_shift_hours"],
        "tau_wake": params["tau_wake"],
        "tau_sleep": params["tau_sleep"],
        "inertia_tau": params["inertia_tau"],
        "homeostatic_scale": params["homeostatic_scale"],
        "debt_scale": params["debt_scale"],
        "debt_saturation_hours": params["debt_saturation_hours"],
        "inertia_scale": params["inertia_scale"],
        "process_s_min": params["process_s_min"],
        "process_s_max": params["process_s_max"],
        "homeostatic_delta_min": params["homeostatic_delta_min"],
        "homeostatic_delta_max": params["homeostatic_delta_max"],
    }

    n_original = sum(1 for p in points if p["original"])
    payload = {
        "model_id": MODEL_ID,
        "fitted_at": datetime.now().isoformat(timespec="seconds"),
        "params": ordered,
        "training": {
            "csv": str(args.csv),
            "fitter": "python/fit_adaptive_sleep_model.py",
            "min_logged_per_day": args.min_logged,
            "max_interpolated_gap_slots": args.max_gap,
            "sleep_need_hours": sleep_need,
            "included_dates": frame["included_dates"],
            "excluded_dates": frame["excluded_dates"],
            "logged_counts": frame["logged_counts"],
            "interpolated_counts": frame["interpolated_counts"],
            "sleep_sources": frame["sleep_sources"],
            "n_days": len(frame["included_dates"]),
            "n_logged_points": sum(
                frame["logged_counts"][d] for d in frame["included_dates"]
            ),
            "n_contextual_logged_points": n_original,
            "n_interpolated_points": len(points) - n_original,
            "n_training_points": len(points),
            "dropped_points": frame["dropped"],
            "validation": "leave-one-day-out; scored on logged points only",
            "cv_mae": result["cv_mae"],
            "cv_rmse": result["cv_rmse"],
            "cv_n": result["cv_n"],
            "in_sample_mae": result["in_sample_mae"],
            "clock_only_cv_mae": result["clock_only_cv_mae"],
            "clock_only_cv_rmse": result["clock_only_cv_rmse"],
            "cv_mae_standard_error": result["cv_mae_standard_error"],
            "n_grid_candidates": result["n_candidates"],
            "n_within_one_se": result["n_within_one_se"],
            "best_grid_cv_mae": result["best_grid_cv_mae"],
            "incumbent_cv_mae": result["incumbent_cv_mae"],
            "corrections_mode": result["corrections_mode"],
            "corrections_help_beyond_noise": result["corrections_help_beyond_noise"],
            "corrections_values_determined": result["corrections_values_determined"],
            "fixed_params": sorted(FIXED_PARAMS),
            "selection": (
                "Fourier baseline solved in closed form by equal-weight-per-day "
                "weighted least squares against the ratings plus each point's "
                "correction; leave-one-day-out scored on logged points only. "
                "Process-S/debt/inertia block: " + result["corrections_selection"] + "."
            ),
        },
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"[fit] wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
