import math
import tkinter as tk

# -----------------------------------------------------------------------------
# FITBIT DATA INPUTS (populate these from the API before drawing)
# -----------------------------------------------------------------------------
#
# Required Fitbit API scopes: sleep | heartrate | activity (optional)
#
# Variable              Fitbit endpoint
# -----------------------------------------------------------------------------
# wake_hour           : /1.2/user/-/sleep/date/{date}.json
#                       -> summary.mainSleep.endTime (parse hour + minute)
#
# sleep_duration_hrs  : same endpoint
#                       -> summary.mainSleep.minutesAsleep / 60
#
# sleep_debt_hours    : /1.2/user/-/sleep/date/{start}/{end}.json (14 days)
#                       -> sum(sleep_need - minutesAsleep/60) for each day
#                       sleep_need = your personal baseline (e.g. 8.0 h)
#
# bathyphase_hour     : /1/user/-/activities/heart/date/{date}/1d/1min.json
#   (Intraday HR)      -> find the clock-hour of lowest avg HR during sleep
#   Requires "Personal" This is the hour of lowest core body temperature.
#   app type on         Circadian peak = bathyphase + 4-6 h
#   dev.fitbit.com      If unavailable, fall back to wake_hour + 10
#
# exertion_factor     : /1/user/-/activities/date/{yesterday}.json (optional)
#   (optional)          -> summary.veryActiveMinutes / 60
#                       Increases effective sleep need when > 1h hard effort
# -----------------------------------------------------------------------------

def two_process_energy(
    t: float,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    circadian_peak_offset: float = 10.0,
) -> float:
    """
    Two-process model of alertness (Borbely 1982, SAFTE).

    Args:
        t                    : Hours since wake-up (0-24).
        sleep_debt_hours     : Accumulated debt over last 14 nights (hours).
        sleep_duration       : Last night's total sleep (hours).
        circadian_peak_offset: Hours after wake when circadian alertness peaks.
                               Derive from bathyphase: peak = bathyphase + 5h,
                               expressed as offset from wake_hour.
                               Falls back to 10.0 if intraday HR unavailable.

    Returns:
        Alertness 0.0-1.0.
    """

    # -- 1. PROCESS S - Homeostatic Sleep Pressure ----------------------------
    # Pressure builds exponentially while awake (tau ~ 18 h to saturate).
    # After sleep it is partially discharged; how much depends on sleep length
    # (tau_sleep ~ 4 h - a 4 h nap halves residual pressure).
    
    tau_wake  = 18.0
    tau_sleep =  4.0
    S_max = 1.0
    S_min = 0.05

    # Residual pressure remaining when the user woke up
    S0 = S_min + (S_max - S_min) * math.exp(-sleep_duration / tau_sleep)

    # Pressure at time t
    S_t = S0 + (S_max - S0) * (1.0 - math.exp(-t / tau_wake))

    # -- 2. PROCESS C - Circadian Alerting Signal -----------------------------
    # Primary 24 h cosine peaks at circadian_peak_offset hours after wake.
    # Secondary 12 h harmonic adds:
    #   * a post-lunch dip (~8 h after wake)
    #   * an evening second wind (~13 h after wake)
    
    C_primary   = 0.55 * math.cos(
        (math.pi / 12) * (t - circadian_peak_offset)
    )
    C_secondary = 0.15 * math.cos(
        (math.pi / 6)  * (t - 8.0)          # trough near 8 h, crest near 14 h
    )
    C_t = 0.50 + C_primary + C_secondary    # centred at 0.50

    # -- 3. RAW ALERTNESS = C(t) - S(t) ---------------------------------------
    raw = C_t - S_t

    # -- 4. SLEEP INERTIA (first ~90 min) -------------------------------------
    tau_inertia = 0.6
    inertia_gate = 1.0 - math.exp(-t / tau_inertia)
    
    # -- 5. DEBT PENALTY (shifts floor down) ----------------------------------
    debt_penalty = min(0.35, sleep_debt_hours / 70.0)
    
    alertness = (raw * inertia_gate) - debt_penalty
    
    return max(0.0, min(1.0, alertness))

_energy_summary_printed = False

def get_energy_level(
    h_clock: float,
    wake_hour: float,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    bathyphase_hour: float = None,
) -> float:
    """
    Wrapper for UI that maps wall-clock time to hours-since-wake.
    """
    global _energy_summary_printed
    
    t = (h_clock - wake_hour) % 24.0

    # Calculate peak_offset from bathyphase
    if bathyphase_hour is not None:
        # Peak is roughly 5 hours after bathyphase
        peak_h = (bathyphase_hour + 5.0) % 24
        peak_offset = (peak_h - wake_hour) % 24
    else:
        peak_offset = 10.0  # sensible population-average fallback

    if not _energy_summary_printed:
        print(f"\n{'='*50}")
        print(f"[get_energy_level] INPUT SUMMARY")
        print(f"  wake_hour        = {wake_hour:.2f}  ({int(wake_hour):02d}:{int((wake_hour % 1)*60):02d})")
        print(f"  sleep_duration   = {sleep_duration:.2f} h")
        print(f"  sleep_debt_hours = {sleep_debt_hours:.2f} h")
        print(f"  bathyphase_hour  = {bathyphase_hour if bathyphase_hour is not None else 'None (using fallback)'}")
        if bathyphase_hour is not None:
             print(f"  [circadian] bathyphase={bathyphase_hour:.1f}h  wake={wake_hour:.1f}h  "
                   f"-> offset={peak_offset:.2f}h after wake")
        print(f"  peak_offset      = {peak_offset:.2f} h after wake")
        print(f"{'-'*50}")
        print(f"  {'Hour':>6}  {'t (h awake)':>11}  {'Energy':>8}")
        print(f"  {'------':>6}  {'-----------':>11}  {'------':>8}")
        for spot_h in [wake_hour, wake_hour+1.5, wake_hour+3.5, wake_hour+8,
                       wake_hour+13, wake_hour+16]:
            spot_h_wrapped = spot_h % 24
            spot_t = (spot_h_wrapped - wake_hour) % 24
            spot_e = two_process_energy(spot_t, sleep_debt_hours, sleep_duration, peak_offset)
            label_map = {
                wake_hour % 24:        "wake",
                (wake_hour+1.5) % 24:  "inertia clears",
                (wake_hour+3.5) % 24:  "morning peak",
                (wake_hour+8)   % 24:  "post-lunch dip",
                (wake_hour+13)  % 24:  "second wind",
                (wake_hour+16)  % 24:  "wind-down",
            }
            label = label_map.get(spot_h_wrapped, "")
            print(f"  {spot_h_wrapped:>5.1f}h  {spot_t:>10.1f}h  {spot_e:>8.3f}  {label}")
        print(f"{'-'*50}\n")
        _energy_summary_printed = True

    return two_process_energy(t, sleep_debt_hours, sleep_duration, peak_offset)

def compute_sleep_debt(
    sleep_logs: list[dict], 
    sleep_need_hours: float,
    include_naps: bool = True
) -> float:
    """
    Computes rolling 14-night sleep debt from Fitbit Sleep API response.
    Groups multiple sessions (naps) by date to calculate total daily rest.
    """
    print(f"\n{'-'*50}")
    print(f"[compute_sleep_debt] sleep_need_hours = {sleep_need_hours:.2f} h")
    print(f"[compute_sleep_debt] include_naps     = {include_naps}")

    # Group sleep by date
    daily_sleep: dict[str, float] = {}
    for log in sleep_logs:
        date = log.get("dateOfSleep")
        if not date: continue
        
        # If naps excluded, only process main sleep
        if not include_naps and not log.get("isMainSleep", False):
            continue
            
        mins = log.get("minutesAsleep", 0)
        daily_sleep[date] = daily_sleep.get(date, 0.0) + (mins / 60.0)

    # Calculate debt for the last 14 unique dates found
    # CRITICAL: Ignore today if no sleep is recorded yet (don't penalize for a day in progress)
    from datetime import datetime
    today_str = datetime.now().strftime("%Y-%m-%d")
    
    sorted_dates = sorted(daily_sleep.keys())
    # Filter out today if it has 0 sleep (means we haven't slept yet)
    active_dates = [d for d in sorted_dates if d != today_str or daily_sleep[d] > 0]
    
    # Take the last 14 completed/recorded days
    window_dates = active_dates[-14:]
    
    total_debt = 0.0
    for date in window_dates:
        actual = daily_sleep[date]
        nightly_debt = max(0.0, sleep_need_hours - actual)
        total_debt  += nightly_debt
        status = "OK " if nightly_debt == 0 else f"DEBT +{nightly_debt:.2f} h"
        print(f"  {date}  slept {actual:.2f} h total  ->  {status}")

    print(f"[compute_sleep_debt] TOTAL DEBT = {total_debt:.2f} h (over {len(window_dates)} days)")
    print(f"{'-'*50}\n")
    return total_debt

def find_bathyphase(intraday_hr: list[dict]) -> float | None:
    """
    Finds the bathyphase (lowest HR clock-hour) from Fitbit intraday HR data.
    """
    print(f"\n{'-'*50}")
    print(f"[find_bathyphase] intraday HR points received = {len(intraday_hr)}")

    if not intraday_hr:
        print("[find_bathyphase] WARNING: no HR data provided -> returning None")
        return None

    buckets: dict[int, list[float]] = {}
    for p in intraday_hr:
        h = int(p['time'].split(':')[0])
        buckets.setdefault(h, []).append(float(p['value']))

    if not buckets:
        return None

    print("[find_bathyphase] avg HR by hour during sleep window:")
    avg_by_hour: dict[int, float] = {}
    for h in sorted(buckets.keys()):
        avg = sum(buckets[h]) / len(buckets[h])
        avg_by_hour[h] = avg
        print(f"  {h:02d}:00  avg HR = {avg:.1f} bpm")

    best_h = min(avg_by_hour, key=avg_by_hour.get)
    print(f"[find_bathyphase] RESULT = {best_h}:00  (avg {avg_by_hour[best_h]:.1f} bpm)")
    print(f"{'-'*50}\n")

    return float(best_h)

class EnergyCurve:
    def __init__(self, canvas, color="#00A8FF"):
        self.canvas = canvas
        self.color  = color
        self.wake_hour         = 7.0
        self.sleep_debt_hours  = 0.0
        self.sleep_duration    = 7.5
        self.bathyphase_hour   = None
        self.normalize         = True

    def interpolate_color(self, val: float) -> str:
        val = max(0.0, min(1.0, val))
        r = int(0   * (1 - val) + 255 * val)
        g = int(210 * (1 - val) + 75  * val)
        b = int(255 * (1 - val) + 43  * val)
        return f'#{r:02x}{g:02x}{b:02x}'

    def draw(self, cx: float, cy: float, radius: float, wake_hour: float):
        self.wake_hour = wake_hour
        steps = 144
        
        # 1. First pass: Collect all energy levels for 24h to allow normalization
        levels = []
        for i in range(steps + 1):
            h = (i / float(steps)) * 24.0
            e = get_energy_level(
                h,
                self.wake_hour,
                self.sleep_debt_hours,
                self.sleep_duration,
                self.bathyphase_hour,
            )
            levels.append(e)

        e_min = min(levels)
        e_max = max(levels)
        e_range = e_max - e_min

        # 2. Second pass: Draw the segments
        last_px = last_py = None
        for i, energy in enumerate(levels):
            h = (i / float(steps)) * 24.0
            
            if self.normalize and e_range > 0.01:
                # Scale so the 24h cycle always spans 10% to 90% radius
                display_energy = (energy - e_min) / e_range
                current_r = (0.10 + 0.80 * display_energy) * radius
            else:
                # Absolute mapping: Energy 0.0 -> center, 1.0 -> 100% radius
                current_r = energy * radius

            angle = (18.0 - h) * 15.0
            rad   = math.radians(angle)
            px = cx + current_r * math.cos(rad)
            py = cy - current_r * math.sin(rad)
            
            if last_px is not None:
                self.canvas.create_line(
                    last_px, last_py, px, py,
                    fill=self.interpolate_color(energy),
                    width=4,
                    capstyle=tk.ROUND,
                    tags="energy_curve",
                )
            last_px, last_py = px, py
