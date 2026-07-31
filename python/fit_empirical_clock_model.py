"""Fit the empirical local-clock energy curve from the alertness CSV.

The fitter is deliberately small and reproducible:

* uses only ``LOGGED`` awake ratings;
* keeps dates with at least ``min_logged`` ratings;
* interpolates only complete interior gaps of one or two 30-minute rows;
* fits a two-harmonic 24-hour Fourier regression with equal weight per day;
* reports leave-one-day-out error on original (not interpolated) ratings.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Iterable

import numpy as np


MODEL_ID = "M7_empirical_clock_fourier"


def _read_rows(csv_path: Path) -> list[dict]:
    rows: list[dict] = []
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        for raw in csv.DictReader(handle):
            dt = datetime.strptime(raw["DateTime"], "%Y-%m-%d %H:%M:%S")
            energy_text = (raw.get("EnergyLevel") or "").strip()
            rows.append(
                {
                    "datetime": dt,
                    "date": dt.date().isoformat(),
                    "hour": dt.hour + dt.minute / 60.0,
                    "status": raw.get("Status", ""),
                    "energy": float(energy_text) if energy_text else None,
                }
            )
    return sorted(rows, key=lambda row: row["datetime"])


def _interpolate_day(day: list[dict], max_gap: int) -> tuple[list[dict], int]:
    values = [row["energy"] if row["status"] == "LOGGED" else None for row in day]
    original = [value is not None for value in values]
    valid = [i for i, value in enumerate(values) if value is not None]
    interpolated = 0

    for left, right in zip(valid[:-1], valid[1:]):
        gap = day[left + 1 : right]
        if not 1 <= len(gap) <= max_gap:
            continue
        if any(row["status"] != "MISSED" for row in gap):
            continue
        left_value = float(values[left])
        right_value = float(values[right])
        for index in range(left + 1, right):
            fraction = (index - left) / (right - left)
            values[index] = left_value + fraction * (right_value - left_value)
            interpolated += 1

    output = []
    for index, row in enumerate(day):
        if values[index] is None:
            continue
        output.append(
            {
                "date": row["date"],
                "hour": row["hour"],
                "energy": float(values[index]),
                "original": original[index],
            }
        )
    return output, interpolated


def _design(hours: Iterable[float]) -> np.ndarray:
    theta = 2.0 * math.pi * np.asarray(list(hours), dtype=float) / 24.0
    return np.column_stack(
        [
            np.ones(len(theta)),
            np.cos(theta),
            np.sin(theta),
            np.cos(2.0 * theta),
            np.sin(2.0 * theta),
        ]
    )


def _fit(rows: list[dict]) -> np.ndarray:
    x = _design(row["hour"] for row in rows)
    y = np.asarray([row["energy"] for row in rows], dtype=float)
    by_day: defaultdict[str, int] = defaultdict(int)
    for row in rows:
        by_day[row["date"]] += 1
    weights = np.asarray([1.0 / by_day[row["date"]] for row in rows], dtype=float)
    weights *= len(by_day) / weights.sum()
    normal = x.T @ (weights[:, None] * x)
    target = x.T @ (weights * y)
    return np.linalg.solve(normal, target)


def _predict(beta: np.ndarray, rows: list[dict]) -> np.ndarray:
    return _design(row["hour"] for row in rows) @ beta


def fit_model(csv_path: Path, min_logged: int = 20, max_gap: int = 2) -> dict:
    raw = _read_rows(csv_path)
    by_date: defaultdict[str, list[dict]] = defaultdict(list)
    for row in raw:
        by_date[row["date"]].append(row)

    logged_counts = {
        date: sum(row["status"] == "LOGGED" and row["energy"] is not None for row in day)
        for date, day in by_date.items()
    }
    included_dates = sorted(date for date, count in logged_counts.items() if count >= min_logged)
    excluded_dates = sorted(date for date, count in logged_counts.items() if count < min_logged)

    processed: list[dict] = []
    interpolated_by_date: dict[str, int] = {}
    for date in included_dates:
        day_rows, interpolated = _interpolate_day(by_date[date], max_gap)
        processed.extend(day_rows)
        interpolated_by_date[date] = interpolated

    original_rows = [row for row in processed if row["original"]]
    cv_errors: list[float] = []
    for holdout in included_dates:
        training = [row for row in processed if row["date"] != holdout]
        testing = [row for row in original_rows if row["date"] == holdout]
        beta = _fit(training)
        cv_errors.extend((_predict(beta, testing) - np.asarray([row["energy"] for row in testing])).tolist())

    beta = _fit(processed)
    curve = _predict(beta, [{"hour": hour} for hour in np.arange(0.0, 24.0, 0.5)])
    return {
        "model_id": MODEL_ID,
        "params": {"fourier_coefs": [float(value) for value in beta], "harmonics": 2},
        "training": {
            "csv": str(csv_path),
            "min_logged_per_day": min_logged,
            "max_interpolated_gap_slots": max_gap,
            "included_dates": included_dates,
            "excluded_dates": excluded_dates,
            "logged_counts": logged_counts,
            "interpolated_counts": interpolated_by_date,
            "n_days": len(included_dates),
            "n_original_points": len(original_rows),
            "n_interpolated_points": sum(interpolated_by_date.values()),
            "cv_mae": float(np.mean(np.abs(cv_errors))),
            "cv_rmse": float(np.sqrt(np.mean(np.square(cv_errors)))),
            "cv_n": len(cv_errors),
            "curve_half_hour_values": [float(value) for value in curve],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--min-logged", type=int, default=20)
    parser.add_argument("--max-gap", type=int, default=2)
    args = parser.parse_args()
    result = fit_model(args.csv, min_logged=args.min_logged, max_gap=args.max_gap)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    training = result["training"]
    print(
        f"{result['model_id']}: {training['n_original_points']} logged + "
        f"{training['n_interpolated_points']} interpolated over {training['n_days']} days; "
        f"LOO MAE={training['cv_mae']:.2f}, RMSE={training['cv_rmse']:.2f}"
    )


if __name__ == "__main__":
    main()
