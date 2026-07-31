#!/usr/bin/env python3
"""
Legacy entry point — delegates to analyze_energy_fit.py.

Previously used a broken hours_since_wake calculation (timestamp % 24h).
This script now runs the full M1–M6 cross-validated analysis and writes
analysis/energy_fit_report.json plus python/personal_energy_fit.json.
"""
from __future__ import annotations

import argparse
import os
import sys

# Allow running from repo root or python/
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from analyze_energy_fit import run_analysis, DEFAULT_CSV, DEFAULT_REPORT, DEFAULT_PLOT, DEFAULT_PERSONAL


def run_optimization(csv_path: str | None = None) -> None:
    repo_root = os.path.dirname(_SCRIPT_DIR)
    csv = csv_path or os.path.join(repo_root, "Alertness_Master_Log.csv")
    if not os.path.exists(csv):
        print(f"Error: Log file not found at {csv}")
        return

    report = run_analysis(
        csv_path=csv,
        report_path=os.path.join(repo_root, "analysis", "energy_fit_report.json"),
        plot_path=os.path.join(repo_root, "analysis", "energy_fit_plots.png"),
        personal_path=os.path.join(_SCRIPT_DIR, "personal_energy_fit.json"),
    )

    winner = report["cv_winner"]
    mae = report["cv_metrics"][winner]["mae"]
    params = report["full_fit_params"].get(winner, {})

    prompt_path = os.path.join(repo_root, "ai_optimization_prompt.txt")
    with open(prompt_path, "w", encoding="utf-8") as f:
        f.write("USER EMPIRICAL DATA SUMMARY:\n")
        f.write(f"- Total Logs: {report['n_logged']}\n")
        f.write(f"- CV Winner: {winner}\n")
        f.write(f"- Leave-one-day-out MAE: {mae:.2f}%\n")
        f.write(f"- Params: {params}\n\n")
        f.write("See analysis/energy_fit_report.json for full M1–M6 comparison.\n")

    print(f"\nCreated '{prompt_path}' with CV summary.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", default=None, help="Path to Alertness_Master_Log.csv")
    args = parser.parse_args()
    run_optimization(args.csv)
