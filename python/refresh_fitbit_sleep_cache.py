"""Refresh ``python/fitbit_sleep_logs.json`` over an explicit date range.

``FitbitClient.get_all_energy_inputs`` only fetches ``[today-14, today]`` and
rewrites the sleep cache wholesale, so it silently drops any older night that is
no longer inside the rolling window.  Model fitting needs the full training
span, so this script fetches an explicit range and *merges* by ``logId``:

* the previous cache is copied to a timestamped backup first;
* fetched records are merged with the cached ones, newest payload winning;
* the file is rewritten in the same schema ``load_sleep_logs`` expects.

Usage::

    python python/refresh_fitbit_sleep_cache.py --start 2026-07-16 --end 2026-08-09
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fitbit_client import FitbitClient, ReauthRequiredError  # noqa: E402

PYTHON_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(PYTHON_DIR, "fitbit_sleep_logs.json")
CONFIG_PATH = os.path.join(PYTHON_DIR, "fitbit_config.json")
TOKEN_PATH = os.path.join(PYTHON_DIR, "fitbit_tokens.json")


def load_cached_logs(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if "sleep_logs" in data:
        return data["sleep_logs"]
    if "inputs" in data and "raw_sleep_logs" in data["inputs"]:
        return data["inputs"]["raw_sleep_logs"]
    return []


def merge_logs(cached: list[dict], fetched: list[dict]) -> list[dict]:
    """Union by logId, preferring the freshly fetched payload."""
    merged: dict[str, dict] = {}
    for log in cached:
        merged[str(log.get("logId", f"{log.get('dateOfSleep')}|{log.get('startTime')}"))] = log
    for log in fetched:
        merged[str(log.get("logId", f"{log.get('dateOfSleep')}|{log.get('startTime')}"))] = log
    return sorted(merged.values(), key=lambda log: (log.get("dateOfSleep", ""), log.get("startTime", "")))


def backup_cache(path: str) -> str | None:
    if not os.path.exists(path):
        return None
    stamp = datetime.now().strftime("%Y%m%dT%H%M%S")
    backup = f"{path}.{stamp}.bak"
    shutil.copy2(path, backup)
    return backup


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", required=True, help="First dateOfSleep, YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="Last dateOfSleep, YYYY-MM-DD")
    parser.add_argument("--cache", default=CACHE_PATH)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Fetch and report coverage without rewriting the cache.",
    )
    args = parser.parse_args()

    with open(CONFIG_PATH, "r", encoding="utf-8") as handle:
        config = json.load(handle)

    client = FitbitClient(
        config["FITBIT_CLIENT_ID"],
        config["FITBIT_CLIENT_SECRET"],
        token_file=TOKEN_PATH,
    )

    try:
        fetched = client.get_sleep_range(args.start, args.end, force=True)
    except ReauthRequiredError as exc:
        print(f"[refresh] Fitbit re-authentication required: {exc}")
        print("[refresh] Cache left untouched. Run the widget's 'relink Fitbit' action, then retry.")
        return 2
    except Exception as exc:  # network, rate limit, ...
        print(f"[refresh] Fetch failed: {exc}")
        print("[refresh] Cache left untouched.")
        return 1

    cached = load_cached_logs(args.cache)
    merged = merge_logs(cached, fetched)

    fetched_dates = {log.get("dateOfSleep") for log in fetched}
    merged_dates = sorted({log.get("dateOfSleep") for log in merged if log.get("dateOfSleep")})
    print(
        f"[refresh] fetched {len(fetched)} record(s) over {len(fetched_dates)} date(s); "
        f"cache had {len(cached)}; merged to {len(merged)} over {len(merged_dates)} date(s)"
    )
    if merged_dates:
        print(f"[refresh] merged coverage {merged_dates[0]} .. {merged_dates[-1]}")
    naps = [log for log in merged if not log.get("isMainSleep")]
    print(f"[refresh] main sleeps: {len(merged) - len(naps)}, naps: {len(naps)}")

    if args.dry_run:
        print("[refresh] --dry-run: cache not written.")
        return 0

    backup = backup_cache(args.cache)
    if backup:
        print(f"[refresh] backed up previous cache to {os.path.basename(backup)}")

    payload = {
        "date": args.end,
        "sleep_logs": merged,
        "timestamp": time.time(),
        "is_real_today": any(log.get("dateOfSleep") == args.end for log in merged),
    }
    with open(args.cache, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
    print(f"[refresh] wrote {args.cache}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
