"""Restore ``SLEEP_EXCLUDED`` markers lost from an alertness CSV export.

``EmpiricalEnergyManager.runRetroactiveSleepFilter`` reverts ``SLEEP_EXCLUDED``
rows back to ``MISSED``/``LOGGED`` whenever they fall outside the app's rolling
14-day Fitbit window, so every export silently loses the sleep windows older
than ``T-14``.  Those markers are the only sleep-timing source for dates the
Fitbit cache no longer covers, and ``empirical_sleep.infer_wake_from_sleep_excluded``
needs a contiguous run to recover wake time.

This merges an older export (the donor, which still has the markers) into a
newer one (the base, which has more ratings) per timestamp:

* the base supplies the row set and every ``EnergyLevel``;
* a donor ``SLEEP_EXCLUDED`` row re-marks the matching base row;
* a base ``LOGGED`` rating inside a donor sleep window is reported as a conflict
  and, by default, yields to ``SLEEP_EXCLUDED`` -- keeping it would split the
  contiguous sleep run that wake inference depends on.

Usage::

    python python/repair_alertness_log.py \
        --base "Alertness_Master_Log (3).csv" \
        --donor "Alertness_Master_Log (2).csv" \
        --output Alertness_Master_Log.csv
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

FIELDNAMES = ["Timestamp", "DateTime", "EnergyLevel", "Status"]
STATUS_SLEEP = "SLEEP_EXCLUDED"
STATUS_LOGGED = "LOGGED"
STATUS_MISSED = "MISSED"


def read_rows(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def _parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "").split(".")[0])


def repair_from_fitbit(base_rows: list[dict]) -> tuple[list[dict], dict]:
    """Re-derive sleep markers from the cached Fitbit sleep intervals.

    Mirrors ``EmpiricalEnergyManager.runRetroactiveSleepFilter``: a slot is
    ``SLEEP_EXCLUDED`` exactly when its timestamp falls inside a Fitbit sleep
    session. Unlike the app, this runs over the full cached span rather than a
    rolling 14-day window, so it restores markers instead of dropping them.
    """
    from analyze_energy_fit import load_sleep_logs

    intervals = [
        (_parse_iso(log["startTime"]), _parse_iso(log["endTime"]))
        for log in load_sleep_logs()
        if log.get("startTime") and log.get("endTime")
    ]
    intervals.sort()
    if not intervals:
        raise SystemExit("[repair] no Fitbit sleep logs available; use --donor mode instead")

    covered = {"start": min(s for s, _ in intervals), "end": max(e for _, e in intervals)}
    stats = Counter()
    conflicts: list[tuple[str, str]] = []
    out: list[dict] = []

    for row in base_rows:
        row = dict(row)
        when = datetime.strptime(row["DateTime"], "%Y-%m-%d %H:%M:%S")
        asleep = any(start <= when < end for start, end in intervals)
        current = row.get("Status")
        has_energy = bool((row.get("EnergyLevel") or "").strip())

        if asleep:
            if current == STATUS_SLEEP:
                stats["already_sleep"] += 1
            else:
                if current == STATUS_LOGGED:
                    conflicts.append((row["DateTime"], row.get("EnergyLevel", "")))
                stats["restored"] += 1
                row["Status"] = STATUS_SLEEP
        elif current == STATUS_SLEEP:
            row["Status"] = STATUS_LOGGED if has_energy else STATUS_MISSED
            stats["cleared"] += 1
        out.append(row)

    out.sort(key=lambda r: r["DateTime"])
    return out, {
        "stats": stats,
        "conflicts": conflicts,
        "n_intervals": len(intervals),
        "covered": covered,
    }


def repair(base_rows: list[dict], donor_rows: list[dict], keep_logged: bool) -> tuple[list[dict], dict]:
    donor_sleep = {
        row["DateTime"] for row in donor_rows if row.get("Status") == STATUS_SLEEP
    }
    stats = Counter()
    conflicts: list[tuple[str, str]] = []
    out: list[dict] = []

    for row in base_rows:
        row = dict(row)
        if row["DateTime"] in donor_sleep:
            current = row.get("Status")
            if current == STATUS_SLEEP:
                stats["already_sleep"] += 1
            elif current == STATUS_LOGGED:
                conflicts.append((row["DateTime"], row.get("EnergyLevel", "")))
                if keep_logged:
                    stats["conflict_kept_logged"] += 1
                else:
                    row["Status"] = STATUS_SLEEP
                    stats["conflict_overridden"] += 1
            else:
                row["Status"] = STATUS_SLEEP
                stats["restored"] += 1
        out.append(row)

    out.sort(key=lambda r: r["DateTime"])
    return out, {"stats": stats, "conflicts": conflicts, "donor_sleep": len(donor_sleep)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True, help="Newer export (more ratings)")
    parser.add_argument("--donor", type=Path, help="Older export (intact sleep markers)")
    parser.add_argument(
        "--from-fitbit",
        action="store_true",
        help="Re-derive markers from the cached Fitbit sleep intervals (authoritative).",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--keep-logged-on-conflict",
        action="store_true",
        help="Keep a base LOGGED rating that falls inside a donor sleep window.",
    )
    args = parser.parse_args()

    base_rows = read_rows(args.base)
    if args.from_fitbit:
        out, report = repair_from_fitbit(base_rows)
        stats = report["stats"]
        print(
            f"[repair] base rows: {len(base_rows)}; {report['n_intervals']} Fitbit sleep "
            f"session(s) covering {report['covered']['start']:%Y-%m-%d} .. {report['covered']['end']:%Y-%m-%d}"
        )
        print(
            f"[repair] restored {stats['restored']} -> SLEEP_EXCLUDED; "
            f"{stats['already_sleep']} already marked; cleared {stats['cleared']} stale marker(s)"
        )
    else:
        if not args.donor:
            parser.error("--donor is required unless --from-fitbit is given")
        donor_rows = read_rows(args.donor)
        out, report = repair(base_rows, donor_rows, args.keep_logged_on_conflict)
        stats = report["stats"]
        print(f"[repair] base rows: {len(base_rows)}, donor sleep rows: {report['donor_sleep']}")
        print(
            f"[repair] restored {stats['restored']} MISSED -> SLEEP_EXCLUDED; "
            f"{stats['already_sleep']} already marked"
        )
    for when, energy in report["conflicts"]:
        verdict = "kept LOGGED" if args.keep_logged_on_conflict else "overridden to SLEEP_EXCLUDED"
        print(f"[repair] conflict {when} energy={energy} -> {verdict}")

    counts = Counter(row["Status"] for row in out)
    print(f"[repair] output status counts: {dict(counts)}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows({key: row.get(key, "") for key in FIELDNAMES} for row in out)
    print(f"[repair] wrote {args.output} ({len(out)} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
