"""Grid-search k_s for sleep-dynamics M4 predictions."""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO, "python"))
os.chdir(os.path.join(REPO, "python"))

from analyze_energy_fit import (  # noqa: E402
    _load_m4_params,
    _m4_pred_at_point,
    build_fitbit_daily_table,
    build_fitting_frame,
    load_empirical_csv,
    load_sleep_logs,
)
from energy_models import ModelParams  # noqa: E402

CSV = os.path.join(REPO, "Alertness_Master_Log.csv")
REPORT = os.path.join(REPO, "analysis", "energy_fit_report.json")
PERSONAL = os.path.join(REPO, "python", "personal_energy_fit.json")


def load_context():
    sleep_logs = load_sleep_logs()
    sleep_need = 8.0
    main_logs = [s for s in sleep_logs if s.get("isMainSleep")]
    if main_logs:
        effs = [
            s.get("minutesAsleep", 0) / s.get("timeInBed", 1)
            if s.get("timeInBed", 0) > 0 else 0.92
            for s in main_logs
        ]
        sleep_need = 8.58 * (sum(effs) / len(effs))
    fitbit_daily = build_fitbit_daily_table(sleep_logs)
    raw = load_empirical_csv(CSV)
    data = build_fitting_frame(raw, fitbit_daily, sleep_logs, sleep_need_hours=sleep_need)
    full_fit = None
    if os.path.exists(REPORT):
        with open(REPORT, encoding="utf-8") as f:
            full_fit = json.load(f).get("full_fit_params")
    p4 = _load_m4_params(data, PERSONAL, REPORT, full_fit)
    return sleep_logs, sleep_need, raw, data, p4


def per_day_mae(data: pd.DataFrame, raw: pd.DataFrame, p4: ModelParams, sleep_logs, sleep_need) -> dict[str, float]:
    maes: dict[str, float] = {}
    for date, day in data.groupby("date"):
        errs = []
        for _, row in day.iterrows():
            dt = pd.to_datetime(row.get("datetime")) if row.get("datetime") else None
            pred = _m4_pred_at_point(
                row, p4, dt,
                sleep_logs=sleep_logs,
                sleep_need_hours=sleep_need,
                raw=raw,
                use_sleep_dynamics=True,
            )
            errs.append(abs(row["y"] - pred))
        maes[str(date)] = float(np.mean(errs)) if errs else 0.0
    return maes


def diagnose_day(date: str, data, raw, p4, sleep_logs, sleep_need, k_s: float) -> None:
    """Print per-day MAE for a single date (verbose helper)."""
    p = ModelParams(**{**p4.to_dict(), "k_s": k_s})
    day = data[data["date"] == date]
    if day.empty:
        return
    errs = []
    for _, row in day.iterrows():
        dt = pd.to_datetime(row["datetime"])
        pred = _m4_pred_at_point(
            row, p, dt,
            sleep_logs=sleep_logs,
            sleep_need_hours=sleep_need,
            raw=raw,
            use_sleep_dynamics=True,
        )
        errs.append(abs(row["y"] - pred))
    print(f"  {date} MAE (k_s={k_s:.2f}): {float(np.mean(errs)):.1f}")


def main() -> None:
    sleep_logs, sleep_need, raw, data, p4 = load_context()
    grid = np.round(np.arange(0.0, 1.55, 0.05), 2)
    best_k = 1.0
    best_score = float("inf")
    results: list[dict] = []

    for k in grid:
        p = ModelParams(**{**p4.to_dict(), "k_s": float(k)})
        maes = per_day_mae(data, raw, p, sleep_logs, sleep_need)
        overall = float(np.mean(list(maes.values())))
        jul2122 = float(np.mean([maes.get("2026-07-21", 0), maes.get("2026-07-22", 0)]))
        # Weight Jul 21-22 equally with overall (owner concern)
        score = 0.5 * overall + 0.5 * jul2122
        results.append({"k_s": float(k), "overall_mae": overall, "jul2122_mae": jul2122, "score": score, **maes})
        if score < best_score:
            best_score = score
            best_k = float(k)

    diagnose_day("2026-07-21", data, raw, p4, sleep_logs, sleep_need, best_k)
    diagnose_day("2026-07-17", data, raw, p4, sleep_logs, sleep_need, best_k)

    print(f"Best k_s={best_k:.2f} (score={best_score:.2f})")
    print("\nTop 5 by score:")
    for r in sorted(results, key=lambda x: x["score"])[:5]:
        print(
            f"  k_s={r['k_s']:.2f} overall={r['overall_mae']:.1f} "
            f"Jul21-22={r['jul2122_mae']:.1f} score={r['score']:.1f}"
        )


if __name__ == "__main__":
    main()
