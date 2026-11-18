#!/usr/bin/env python3
"""
Data review workflow: suspicious points, unlogged gaps, nap-aware model fills,
and dynamic peak/trough detection.
"""
from __future__ import annotations

import json
import os
from datetime import datetime
from typing import Any

import numpy as np
import pandas as pd

from empirical_sleep import (
    build_inferred_sleep_episodes,
    build_wake_episodes,
    wake_context_for_datetime,
)
from analyze_energy_fit import (
    DEFAULT_CSV,
    DEFAULT_OUTLIER_REPORT_JSON,
    DEFAULT_PERSONAL,
    DEFAULT_REPORT,
    STATUS_LOGGED,
    STATUS_MISSED,
    STATUS_SLEEP,
    _format_clock_hour,
    _load_m4_params,
    build_fitbit_daily_table,
    build_fitting_frame,
    compute_m4_residual_frame,
    debt_as_of_date,
    load_empirical_csv,
    load_sleep_logs,
)
from energy_models import (
    ModelParams,
    find_energy_extrema,
    predict_m4_nap_adjusted,
)
from sleep_dynamics import (
    SleepEpisode,
    dynamics_at,
    predict_energy_sleep_aware,
    projected_wake_energy,
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DATA_REVIEW_JSON = os.path.join(REPO_ROOT, "analysis", "data_review_report.json")
DEFAULT_DATA_REVIEW_MD = os.path.join(REPO_ROOT, "analysis", "data_review_report.md")

STATUS_MODEL_GUESS = "MODEL_GUESS"


def _parse_iso_datetime(iso_str: str) -> datetime:
    cleaned = iso_str.replace("Z", "")
    if "." in cleaned:
        cleaned = cleaned.split(".")[0]
    return datetime.fromisoformat(cleaned)


def build_nap_events(sleep_logs: list[dict]) -> list[dict[str, Any]]:
    """Intraday nap sessions (non-main sleep)."""
    naps: list[dict[str, Any]] = []
    for log in sleep_logs:
        if log.get("isMainSleep", False):
            continue
        start = log.get("startTime")
        end = log.get("endTime")
        if not start or not end:
            continue
        start_dt = _parse_iso_datetime(start)
        end_dt = _parse_iso_datetime(end)
        naps.append({
            "date": log.get("dateOfSleep", start_dt.date().isoformat()),
            "start_datetime": start_dt,
            "end_datetime": end_dt,
            "duration_hours": log.get("minutesAsleep", 0) / 60.0,
            "start_hour": start_dt.hour + start_dt.minute / 60.0,
            "end_hour": end_dt.hour + end_dt.minute / 60.0,
        })
    return sorted(naps, key=lambda n: n["start_datetime"])


def audit_unlogged_gaps(
    raw: pd.DataFrame,
    p4: ModelParams,
    nap_events: list[dict[str, Any]],
    sleep_logs: list[dict],
    sleep_need: float,
    fourier_coefs: list[float] | None = None,
) -> list[dict[str, Any]]:
    """
    Recommend fill strategy for each MISSED slot.
    - INTERPOLATE: between two LOGGED points, no sleep between
    - MODEL_GUESS: model prediction when no bracketing logs (wake period)
    - SLEEP_RESTORE: active homeostatic recovery during sleep (no subjective value)
    - POST_WAKE_MODEL: model from recovered state after a sleep boundary
    """
    wake_eps = build_wake_episodes(sleep_logs)
    inferred_eps = build_inferred_sleep_episodes(raw)
    proposals: list[dict[str, Any]] = []

    for date, day in raw.groupby("date"):
        day = day.sort_values("DateTime")
        logged_idx = day.index[day["Status"] == STATUS_LOGGED].tolist()

        for idx, row in day.iterrows():
            if row["Status"] not in (STATUS_MISSED, STATUS_SLEEP):
                continue
            point_dt = row["DateTime"]
            if not isinstance(point_dt, datetime):
                point_dt = pd.Timestamp(point_dt).to_pydatetime()

            dyn = dynamics_at(point_dt, sleep_logs, sleep_need, p4, extra_episodes=inferred_eps)
            sleep_aware = predict_energy_sleep_aware(
                point_dt, sleep_logs, sleep_need, p4, nap_events, fourier_coefs,
                extra_episodes=inferred_eps,
            )

            if sleep_aware.get("in_sleep") or row["Status"] == STATUS_SLEEP:
                proj = sleep_aware.get("projected_wake_energy")
                if proj is None and dyn is not None:
                    proj = projected_wake_energy(
                        dyn, sleep_logs, sleep_need, p4, fourier_coefs,
                        extra_episodes=inferred_eps,
                    )
                ep = dyn.active_episode if dyn else None
                kind = "main sleep" if ep and ep.is_main else "nap/rest"
                proposals.append({
                    "datetime": str(point_dt),
                    "date": date,
                    "hour": float(row["hour"]),
                    "action": "SLEEP_RESTORE",
                    "proposed_value": None,
                    "confidence": "model",
                    "s_pressure": round(float(sleep_aware.get("s_pressure", 0)), 3),
                    "recovery_fraction": round(float(sleep_aware.get("recovery_fraction", 0)), 3),
                    "projected_wake_energy": proj,
                    "reason": (
                        f"Active Process-S restoration during {kind} "
                        f"({sleep_aware.get('recovery_fraction', 0):.0%} discharged toward minimum)"
                    ),
                })
                continue

            ctx = wake_context_for_datetime(
                point_dt, wake_eps, sleep_logs, sleep_need, include_naps=True
            )
            if ctx is None:
                proposals.append({
                    "datetime": str(point_dt),
                    "date": date,
                    "hour": float(row["hour"]),
                    "action": "SKIP",
                    "proposed_value": None,
                    "confidence": "n/a",
                    "reason": "No wake episode context",
                })
                continue

            prev_logged = day.loc[[i for i in logged_idx if i < idx]]
            next_logged = day.loc[[i for i in logged_idx if i > idx]]
            has_sleep_between = False
            if not prev_logged.empty and not next_logged.empty:
                between = day.loc[prev_logged.index[-1]:next_logged.index[0]]
                has_sleep_between = (between["Status"] == STATUS_SLEEP).any()

            if has_sleep_between:
                guess = sleep_aware.get("energy")
                if guess is None:
                    guess = predict_m4_nap_adjusted(
                        ctx["t"],
                        ctx["sleep_duration"],
                        ctx["sleep_debt_hours"],
                        ctx["sleep_efficiency"],
                        p4,
                        point_dt,
                        nap_events,
                        fourier_coefs=fourier_coefs,
                        sleep_logs=sleep_logs,
                        sleep_need_hours=sleep_need,
                        extra_episodes=inferred_eps,
                    )
                proposals.append({
                    "datetime": str(point_dt),
                    "date": date,
                    "hour": float(row["hour"]),
                    "action": "POST_WAKE_MODEL",
                    "proposed_value": round(float(guess), 1),
                    "confidence": "medium",
                    "s_pressure": round(float(sleep_aware.get("s_pressure", 0)), 3),
                    "reason": "Gap crosses sleep — subjective value from post-restoration model state",
                })
                continue

            if (
                not prev_logged.empty
                and not next_logged.empty
                and prev_logged.iloc[-1]["EnergyLevel"] is not None
                and next_logged.iloc[0]["EnergyLevel"] is not None
            ):
                a = float(prev_logged.iloc[-1]["EnergyLevel"])
                b = float(next_logged.iloc[0]["EnergyLevel"])
                n_between = len(day.loc[prev_logged.index[-1]:next_logged.index[0]]) - 2
                pos = list(day.loc[prev_logged.index[-1]:next_logged.index[0]].index).index(idx)
                frac = pos / max(1, n_between + 1)
                val = round(a + frac * (b - a), 1)
                proposals.append({
                    "datetime": str(point_dt),
                    "date": date,
                    "hour": float(row["hour"]),
                    "action": "INTERPOLATE",
                    "proposed_value": val,
                    "confidence": "high",
                    "reason": "Linear between bracketing LOGGED points",
                })
                continue

            guess = predict_m4_nap_adjusted(
                ctx["t"],
                ctx["sleep_duration"],
                ctx["sleep_debt_hours"],
                ctx["sleep_efficiency"],
                p4,
                point_dt,
                nap_events,
                fourier_coefs=fourier_coefs,
                sleep_logs=sleep_logs,
                sleep_need_hours=sleep_need,
                extra_episodes=inferred_eps,
            )
            gap_mins = 999.0
            if not prev_logged.empty:
                prev_dt = prev_logged.iloc[-1]["DateTime"]
                gap_mins = (point_dt - pd.Timestamp(prev_dt).to_pydatetime()).total_seconds() / 60.0

            confidence = "medium"
            reason = "M4+nap model guess (no bracketing logs)"
            if gap_mins >= 90:
                confidence = "low"
                reason = f"Model guess after {gap_mins:.0f} min gap — review manually"

            proposals.append({
                "datetime": str(point_dt),
                "date": date,
                "hour": float(row["hour"]),
                "action": "MODEL_GUESS",
                "proposed_value": round(guess, 1),
                "confidence": confidence,
                "reason": reason,
            })

    return proposals


def predict_daily_extrema(
    ctx: dict[str, Any],
    p4: ModelParams,
    nap_events: list[dict[str, Any]],
    fourier_coefs: list[float] | None,
    sleep_logs: list[dict],
    sleep_need_hours: float,
    inferred_eps: list[SleepEpisode] | None = None,
    t_max: float = 18.0,
    step: float = 0.25,
) -> list[dict[str, Any]]:
    """Predict energy curve for one wake episode and return peaks/troughs."""
    t_grid = np.arange(0.0, t_max + step, step)
    wake_h = float(ctx["wake_hour"])
    wake_dt = ctx.get("wake_datetime")
    if wake_dt is None:
        wake_dt = datetime(2000, 1, 1)

    energies = []
    for t in t_grid:
        point_dt = wake_dt + pd.Timedelta(hours=float(t))
        pdt = point_dt.to_pydatetime() if hasattr(point_dt, "to_pydatetime") else point_dt
        e = predict_m4_nap_adjusted(
            float(t),
            ctx["sleep_duration"],
            ctx["sleep_debt_hours"],
            ctx["sleep_efficiency"],
            p4,
            pdt,
            nap_events,
            fourier_coefs=fourier_coefs,
            sleep_logs=sleep_logs,
            sleep_need_hours=sleep_need_hours,
            extra_episodes=inferred_eps,
        )
        energies.append(e)

    extrema = find_energy_extrema(t_grid, energies, wake_h, min_prominence=2.5)
    for ex in extrema:
        ex["clock_label"] = _format_clock_hour(ex["clock_hour"])
    return extrema


def run_data_review(
    csv_path: str = DEFAULT_CSV,
    report_path: str = DEFAULT_REPORT,
    personal_path: str = DEFAULT_PERSONAL,
    outlier_json: str = DEFAULT_OUTLIER_REPORT_JSON,
    json_path: str = DEFAULT_DATA_REVIEW_JSON,
    md_path: str = DEFAULT_DATA_REVIEW_MD,
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

    fitbit_daily = build_fitbit_daily_table(sleep_logs, include_naps=True)
    raw = load_empirical_csv(csv_path)
    frame_cal = build_fitting_frame(
        raw, fitbit_daily, sleep_logs, sleep_need_hours=sleep_need, grouping="calendar",
    )
    frame_wake = build_fitting_frame(
        raw, fitbit_daily, sleep_logs, sleep_need_hours=sleep_need, grouping="wake_episode",
    )

    full_fit = None
    if os.path.exists(report_path):
        with open(report_path, "r", encoding="utf-8") as f:
            full_fit = json.load(f).get("full_fit_params")
    p4 = _load_m4_params(frame_wake, personal_path, report_path, full_fit)

    fourier_coefs: list[float] | None = None
    if full_fit and "M5_mixed_fourier" in full_fit:
        fourier_coefs = full_fit["M5_mixed_fourier"].get("params", {}).get("fourier_coefs")

    nap_events = build_nap_events(sleep_logs)
    gap_proposals = audit_unlogged_gaps(raw, p4, nap_events, sleep_logs, sleep_need, fourier_coefs)
    inferred_eps = build_inferred_sleep_episodes(raw)

    suspicious: list[dict[str, Any]] = []
    if os.path.exists(outlier_json):
        with open(outlier_json, "r", encoding="utf-8") as f:
            outlier_report = json.load(f)
        suspicious = [
            o for o in outlier_report.get("top_outliers", [])
            if o.get("classification") in ("data_suspect", "alignment_artifact", "ambiguous")
        ]

    residual_cal = compute_m4_residual_frame(
        frame_cal, raw, p4, sleep_logs=sleep_logs, sleep_need_hours=sleep_need,
    )
    residual_wake = compute_m4_residual_frame(
        frame_wake, raw, p4, sleep_logs=sleep_logs, sleep_need_hours=sleep_need,
    )
    mae_cal = float(residual_cal["abs_error"].mean()) if not residual_cal.empty else 0.0
    mae_wake = float(residual_wake["abs_error"].mean()) if not residual_wake.empty else 0.0

    # Predict peaks/troughs for each calendar day that has LOGGED data
    logged_rows = raw[raw["Status"] == STATUS_LOGGED]
    wake_eps_all = build_wake_episodes(sleep_logs)
    daily_extrema: dict[str, list[dict[str, Any]]] = {}
    frame_by_date = frame_cal.groupby("date").first()
    for date in sorted(logged_rows["date"].unique()):
        if date not in frame_by_date.index:
            continue
        row = frame_by_date.loc[date]
        wake_dt = None
        wake_eps_row = wake_eps_all[wake_eps_all["date"] <= str(date)]
        if not wake_eps_row.empty:
            wake_dt = wake_eps_row.iloc[-1]["wake_datetime"]
        ctx = {
            "wake_hour": float(row["wake_hour"]),
            "wake_datetime": wake_dt or datetime.strptime(str(date), "%Y-%m-%d"),
            "sleep_duration": float(row["sleep_duration"]),
            "sleep_debt_hours": float(row["sleep_debt_hours"]),
            "sleep_efficiency": float(row["sleep_efficiency"]),
        }
        ext = predict_daily_extrema(
            ctx, p4, nap_events, fourier_coefs, sleep_logs, sleep_need, inferred_eps
        )
        daily_extrema[str(date)] = ext

    n_missed = int((raw["Status"] == STATUS_MISSED).sum())
    n_interpolate = sum(1 for g in gap_proposals if g["action"] == "INTERPOLATE")
    n_guess = sum(1 for g in gap_proposals if g["action"] == "MODEL_GUESS")
    n_sleep_restore = sum(1 for g in gap_proposals if g["action"] == "SLEEP_RESTORE")
    n_post_wake = sum(1 for g in gap_proposals if g["action"] == "POST_WAKE_MODEL")
    n_skip = sum(1 for g in gap_proposals if g["action"] == "SKIP")

    report: dict[str, Any] = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "n_logged": int((raw["Status"] == STATUS_LOGGED).sum()),
        "n_missed": n_missed,
        "n_naps": len(nap_events),
        "mae_m4_calendar_grouping": mae_cal,
        "mae_m4_wake_episode_grouping": mae_wake,
        "gap_proposals_summary": {
            "INTERPOLATE": n_interpolate,
            "MODEL_GUESS": n_guess,
            "SLEEP_RESTORE": n_sleep_restore,
            "POST_WAKE_MODEL": n_post_wake,
            "SKIP": n_skip,
        },
        "nap_events": [
            {
                "date": n["date"],
                "start": n["start_datetime"].isoformat(sep=" "),
                "end": n["end_datetime"].isoformat(sep=" "),
                "duration_hours": n["duration_hours"],
            }
            for n in nap_events
        ],
        "suspicious_for_review": suspicious[:15],
        "gap_proposals": gap_proposals,
        "daily_predicted_extrema": daily_extrema,
        "walkthrough": _build_walkthrough(suspicious, gap_proposals, nap_events, mae_cal, mae_wake),
    }

    os.makedirs(os.path.dirname(json_path), exist_ok=True)
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, default=str)

    _write_data_review_md(report, md_path)

    print("\n" + "=" * 60)
    print("DATA REVIEW")
    print("=" * 60)
    print(f"MISSED slots: {n_missed} | Naps: {len(nap_events)}")
    print(
        f"Gap proposals: {n_interpolate} INTERPOLATE, {n_guess} MODEL_GUESS, "
        f"{n_sleep_restore} SLEEP_RESTORE, {n_post_wake} POST_WAKE_MODEL, {n_skip} SKIP"
    )
    print(f"MAE calendar grouping: {mae_cal:.1f} -> wake-episode: {mae_wake:.1f}")
    print(f"\nMarkdown: {md_path}")
    print(f"JSON: {json_path}")
    return report


def _build_walkthrough(
    suspicious: list[dict],
    gaps: list[dict],
    naps: list[dict],
    mae_cal: float,
    mae_wake: float,
) -> str:
    parts = [
        "## How to review suspicious data",
        "",
        "1. **data_suspect** outliers (from m4_outlier_report): check the daily plot red markers. "
        "If the point is an isolated spike amid smooth neighbors, consider correcting or excluding it.",
        "2. **alignment_artifact** points: logged before the calendar-date Fitbit wake — not bad data; "
        "the pipeline used wrong sleep inputs. Re-fit M4 with wake-episode grouping before switching "
        f"(current comparison: calendar MAE {mae_cal:.1f} vs wake-episode {mae_wake:.1f}).",
        "3. **model_limit** clusters: real ratings where M4's fixed phase is wrong — keep logging; "
        "do not delete.",
        "",
        "## Unlogged regions (MISSED slots)",
        "",
        "- **INTERPOLATE** (high confidence): linear fill between two LOGGED points in the same wake period.",
        "- **MODEL_GUESS** (medium/low): M4 + sleep-dynamics model when no bracketing logs.",
        "- **SLEEP_RESTORE**: active Process-S homeostatic discharge during sleep — no subjective value, "
        "but `recovery_fraction` and `projected_wake_energy` describe how wake predictions will shift.",
        "- **POST_WAKE_MODEL**: gap crosses sleep; value comes from restored post-sleep model state.",
        "- **SKIP**: only when no wake context exists.",
        "",
        "## Sleep as an active force",
        "",
        "Main sleep and naps discharge homeostatic pressure (Process S) via `sleep_dynamics.py`. "
        "Upon wake, reduced S pressure and nap inertia feed into `predict_energy_sleep_aware()`. "
        "This replaces treating sleep slots as merely ignored.",
        "",
        "## Naps",
        "",
    ]
    if naps:
        parts.append(f"Found **{len(naps)}** Fitbit nap(s). Model adds recovery boost for ~3h after each nap end.")
        for n in naps[:5]:
            parts.append(
                f"- {n['date']} {_format_clock_hour(n['start_hour'])}–"
                f"{_format_clock_hour(n['end_hour'])} ({n['duration_hours']:.1f}h)"
            )
    else:
        parts.append("No Fitbit naps in loaded sleep logs. Nap boost will be zero until nap data exists.")

    parts.extend([
        "",
        "## Dynamic peaks and troughs",
        "",
        "Predicted extrema use M4 + Fourier + sleep-dynamics S penalty on a 15-min grid. "
        "Naps create local S discharge and short inertia, which can introduce secondary peaks/troughs.",
    ])
    return "\n".join(parts)


def _write_data_review_md(report: dict[str, Any], md_path: str) -> None:
    lines = [
        "# Data Review Report",
        "",
        f"Generated: {report['generated_at']}",
        "",
        report.get("walkthrough", ""),
        "",
        "## Gap fill proposals",
        "",
        "| DateTime | Action | Value | Confidence | Reason |",
        "|---|---|---:|---|---|",
    ]
    for g in report.get("gap_proposals", [])[:40]:
        val = g.get("proposed_value")
        val_s = f"{val:.1f}" if val is not None else "—"
        extra = ""
        if g.get("action") == "SLEEP_RESTORE":
            rec = g.get("recovery_fraction")
            proj = g.get("projected_wake_energy")
            extra = f" | recovery={rec:.0%}" if rec is not None else ""
            if proj is not None:
                extra += f" wake≈{proj:.0f}"
        lines.append(
            f"| {g['datetime']} | {g['action']} | {val_s} | {g['confidence']} | {g['reason']}{extra} |"
        )

    lines.extend(["", "## Suspicious points to review", ""])
    for s in report.get("suspicious_for_review", [])[:10]:
        lines.append(
            f"- **{s.get('datetime', '')}** obs={s.get('y')} pred={s.get('m4_pred', 0):.1f} "
            f"({s.get('classification')}): {s.get('rationale', '')}"
        )

    lines.extend(["", "## Predicted peaks and troughs per wake day", ""])
    for date, extrema in report.get("daily_predicted_extrema", {}).items():
        lines.append(f"### {date}")
        if not extrema:
            lines.append("_No prominent extrema detected (flat or low prominence)._")
            continue
        for ex in extrema:
            lines.append(
                f"- **{ex['type']}** at {_format_clock_hour(ex['clock_hour'])} "
                f"(t={ex['t']:.1f}h, energy≈{ex['energy']:.0f}, prominence={ex['prominence']:.1f})"
            )
        lines.append("")

    os.makedirs(os.path.dirname(md_path), exist_ok=True)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    run_data_review()
