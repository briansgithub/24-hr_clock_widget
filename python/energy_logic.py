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
    clamp: bool = True,
    tau_wake: float = 23.0,
    tau_sleep: float = 4.0,
    tau_inertia: float = 1.5,
    debt_factor: float = 1.0,
) -> float:
    """
    Two-process model of alertness (Borbely 1982, SAFTE).

    Args:
        t                    : Hours since wake-up (0-24).
        sleep_debt_hours     : Accumulated debt over last 14 nights (hours).
        sleep_duration       : Last night's total sleep (hours).
        circadian_peak_offset: Hours after wake when circadian alertness peaks.
                               Derive from bathyphase: peak = bathyphase + 15h,
                               expressed as offset from wake_hour.
                               Falls back to 10.0 if intraday HR unavailable.
        tau_wake             : Time constant for build-up of sleep pressure (h).
        tau_sleep            : Time constant for dissipation of sleep pressure (h).
        tau_inertia          : Time constant for clearing sleep inertia (h).
        debt_factor          : Sensitivity multiplier for sleep debt penalty.

    Returns:
        Alertness 0.0-1.0.
    """

    # -- 1. PROCESS S - Homeostatic Sleep Pressure ----------------------------
    # Pressure builds exponentially while awake (tau ~ 18 h to saturate).
    # After sleep it is partially discharged; how much depends on sleep length
    # (tau_sleep ~ 4 h - a 4 h nap halves residual pressure).
    
    S_max = 1.0
    S_min = 0.05

    # Steady-state boundary conditions for continuous 24h loop
    sleep_start = 24.0 - sleep_duration
    E_w = math.exp(-sleep_start / tau_wake)
    E_s = math.exp(-sleep_duration / tau_sleep)
    
    # Avoid division by zero in pathological cases
    divisor = 1.0 - E_w * E_s
    if divisor < 0.0001:
        divisor = 0.0001
        
    S0 = (S_max * E_s * (1.0 - E_w) + S_min * (1.0 - E_s)) / divisor
    S_bed = S0 * E_w + S_max * (1.0 - E_w)

    # Pressure at time t
    if t < sleep_start:
        # Awake: pressure builds
        S_t = S0 + (S_max - S0) * (1.0 - math.exp(-t / tau_wake))
    else:
        # Asleep: pressure discharges
        t_sleep = t - sleep_start
        S_t = S_min + (S_bed - S_min) * math.exp(-t_sleep / tau_sleep)

    # -- 2. PROCESS C - Circadian Alerting Signal -----------------------------
    # Primary 24 h cosine peaks at circadian_peak_offset hours after wake.
    # Secondary 12 h harmonic adds:
    #   * a post-lunch dip (~8 h after wake)
    #   * an evening second wind (~13 h after wake)
    
    C_primary   = 0.55 * math.cos(
        (math.pi / 12) * (t - circadian_peak_offset)
    )
    C_secondary = -0.15 * math.cos(
        (math.pi / 6)  * (t - 8.0)          # trough near 8 h, crest near 14 h
    )
    C_t = 1.10 + C_primary + C_secondary    # shifted so morning energy is positive

    # -- 3. RAW ALERTNESS = C(t) - S(t) ---------------------------------------
    raw = C_t - S_t

    # -- 4. SLEEP INERTIA (first ~90 min) -------------------------------------
    # Process W: Alertness is suppressed immediately after waking.
    # W(t) = (raw_at_wake) * exp(-t / tau_inertia)
    # We calculate raw_at_wake (t=0) to ensure alertness starts at 0.0.
    C_at_wake = 1.10 + 0.55 * math.cos((math.pi / 12) * (-circadian_peak_offset)) + \
                -0.15 * math.cos((math.pi / 6) * (-8.0))
    raw_at_wake = C_at_wake - S0
    inertia_penalty = max(0.0, raw_at_wake) * math.exp(-t / tau_inertia)
    
    # -- 5. DEBT PENALTY (shifts floor down) ----------------------------------
    # Weighted debt of 25 hours (e.g. 1.8h deficit for 14 days) results in max penalty.
    debt_penalty = min(0.35, (sleep_debt_hours * debt_factor) / 25.0)
    
    alertness = raw - inertia_penalty - debt_penalty
    
    if clamp:
        return max(0.0, min(1.0, alertness))
    return alertness

_energy_summary_printed = False

def get_energy_level(
    h_clock: float,
    wake_hour: float,
    sleep_debt_hours: float = 0.0,
    sleep_duration: float = 7.5,
    bathyphase_hour: float = None,
    clamp: bool = True,
    tau_wake: float = 23.0,
    tau_sleep: float = 4.0,
    tau_inertia: float = 1.5,
    debt_factor: float = 1.0,
    circadian_peak_offset: float = None,
) -> float:
    """
    Wrapper for UI that maps wall-clock time to hours-since-wake.
    """
    global _energy_summary_printed
    
    t = (h_clock - wake_hour) % 24.0

    # Calculate peak_offset
    if circadian_peak_offset is not None:
        peak_offset = circadian_peak_offset
    elif bathyphase_hour is not None:
        # WMZ peak is roughly 15 hours after bathyphase
        peak_h = (bathyphase_hour + 15.0) % 24
        peak_offset = (peak_h - wake_hour) % 24
    else:
        peak_offset = 13.0  # sensible population-average fallback (15h post-bathy, assuming 2h pre-wake nadir)

    if not _energy_summary_printed:
        # (Logging omitted for brevity in multi_replace, but in practice keep it)
        pass

    return two_process_energy(
        t, 
        sleep_debt_hours, 
        sleep_duration, 
        peak_offset, 
        clamp,
        tau_wake=tau_wake,
        tau_sleep=tau_sleep,
        tau_inertia=tau_inertia,
        debt_factor=debt_factor
    )

def compute_sleep_debt(
    sleep_logs: list[dict], 
    sleep_need_hours: float,
    include_naps: bool = True,
    excluded_dates: list[str] = None
) -> float:
    """
    Computes a weighted 14-night sleep debt.
    Recent nights have a higher impact on the final score.

    If a date is in excluded_dates, it is skipped entirely.
    If a date is NOT in excluded_dates but has no log, it is treated as 0.0h sleep.
    """
    if excluded_dates is None:
        excluded_dates = []

    # Group sleep by date
    daily_sleep = {}
    for log in sleep_logs:
        date = log.get("dateOfSleep")
        if not date or (not include_naps and not log.get("isMainSleep", False)):
            continue
        daily_sleep[date] = daily_sleep.get(date, 0.0) + (log.get("minutesAsleep", 0) / 60.0)

    from datetime import datetime, timedelta
    today = datetime.now()
    
    # We look at the last 14 nights (T-0 to T-14)
    # Note: T-0 is usually excluded by default in the UI, but we process it if not excluded.
    weighted_debt = 0.0
    decay_factor = 0.9  # Each day back is 90% as impactful as the day after it
    
    print(f"\n[compute_sleep_debt] Calculating Weighted Debt (Decay={decay_factor}):")
    print(f"  Excluding dates: {excluded_dates}")

    # Process T-0 down to T-14
    for i in range(15):
        date_obj = today - timedelta(days=i)
        date_str = date_obj.strftime("%Y-%m-%d")

        if date_str in excluded_dates:
            print(f"  {date_str} (t-{i}): EXCLUDED")
            continue

        actual = daily_sleep.get(date_str, 0.0)
        nightly_debt = sleep_need_hours - actual
        
        # Apply weight: i=0 (today/last night) has weight 0.9^0 = 1.0
        weight = math.pow(decay_factor, i)
        contribution = nightly_debt * weight
        weighted_debt += contribution
        
        log_status = "logged" if date_str in daily_sleep else "MISSING (0h)"
        print(f"  {date_str} (t-{i}): {actual:.2f}h sleep ({log_status}) | Debt: {nightly_debt:.2f}h | Weight: {weight:.2f}")

    print(f"[compute_sleep_debt] FINAL WEIGHTED DEBT = {weighted_debt:.2f} h")
    return weighted_debt

def find_bathyphase(intraday_hr: list[dict]) -> float | None:
    """
    Finds the bathyphase (lowest HR clock-hour) from Fitbit intraday HR data.
    Uses Parabolic Vertex Fit for sub-hour precision.
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

    min_h = min(avg_by_hour, key=avg_by_hour.get)
    
    # Parabolic Fit
    y2 = avg_by_hour[min_h]
    prev_h = (min_h - 1) % 24
    next_h = (min_h + 1) % 24
    
    y1 = avg_by_hour.get(prev_h)
    y3 = avg_by_hour.get(next_h)
    
    if y1 is not None and y3 is not None:
        denom = y1 - 2*y2 + y3
        if abs(denom) > 0.0001:
            offset = -0.5 * (y3 - y1) / denom
            if abs(offset) <= 1.0:
                result = (min_h + offset) % 24
                print(f"[find_bathyphase] RESULT = {result:.2f} (Parabolic Fit, offset={offset:.2f})")
                print(f"{'-'*50}\n")
                return result

    print(f"[find_bathyphase] RESULT = {min_h:.2f} (Simple Minimum)")
    print(f"{'-'*50}\n")

    return float(min_h)

class EnergyCurve:
    def __init__(self, canvas, color="#00A8FF"):
        self.canvas = canvas
        self.color  = color
        self.wake_hour         = 7.0
        self.sleep_debt_hours  = 0.0
        self.sleep_duration    = 7.5
        self.bathyphase_hour   = None
        self.normalize         = True
        
        # Model Parameters
        self.tau_wake     = 23.0
        self.tau_sleep    = 4.0
        self.tau_inertia  = 1.5
        self.debt_factor   = 1.0
        self.circadian_peak_offset = None # If None, use bathyphase logic

        # Cache variables
        self._cached_args = None
        self._cached_levels = []
        self._cached_e_min = 0.0
        self._cached_e_max = 0.0

    def interpolate_color(self, val: float) -> str:
        val = max(0.0, min(1.0, val))
        r = int(0   * (1 - val) + 255 * val)
        g = int(210 * (1 - val) + 75  * val)
        b = int(255 * (1 - val) + 43  * val)
        return f'#{r:02x}{g:02x}{b:02x}'

    def get_cached_energy(self, h: float, steps: int = 1440) -> float:
        """Returns the energy level at hour `h` using a precomputed cache."""
        args = (
            self.wake_hour, self.sleep_debt_hours, self.sleep_duration, self.bathyphase_hour,
            self.tau_wake, self.tau_sleep, self.tau_inertia, self.debt_factor, self.circadian_peak_offset
        )
        if self._cached_args != args or not self._cached_levels or len(self._cached_levels) != steps + 1:
            self._recompute_cache(steps)
            
        idx = int(round((h % 24.0) / 24.0 * steps)) % steps
        return self._cached_levels[idx]

    def _recompute_cache(self, steps: int):
        global _energy_summary_printed
        _energy_summary_printed = False
        levels = []
        for i in range(steps + 1):
            h = (i / float(steps)) * 24.0
            e = get_energy_level(
                h,
                self.wake_hour,
                self.sleep_debt_hours,
                self.sleep_duration,
                self.bathyphase_hour,
                clamp=False,
                tau_wake=self.tau_wake,
                tau_sleep=self.tau_sleep,
                tau_inertia=self.tau_inertia,
                debt_factor=self.debt_factor,
                circadian_peak_offset=self.circadian_peak_offset,
            )
            levels.append(e)
        self._cached_levels = levels
        self._cached_e_min = min(levels)
        self._cached_e_max = max(levels)
        self._cached_args = (
            self.wake_hour, self.sleep_debt_hours, self.sleep_duration, self.bathyphase_hour,
            self.tau_wake, self.tau_sleep, self.tau_inertia, self.debt_factor, self.circadian_peak_offset
        )

    def get_display_radius(self, energy: float, radius: float) -> float:
        """Calculates the display radius for a given energy level and base radius."""
        e_min = self._cached_e_min
        e_max = self._cached_e_max
        e_range = e_max - e_min
        
        if self.normalize and e_range > 0.01:
            # Scale so the 24h cycle always spans 10% to 90% radius
            display_energy = (energy - e_min) / e_range
            return (0.10 + 0.80 * display_energy) * radius
        else:
            # Absolute mapping: clamp to 1.0, scale to 95% radius to avoid exceeding circumference
            clamped_energy = max(0.0, min(1.0, energy))
            return clamped_energy * (radius * 0.95)

    def draw(self, cx: float, cy: float, radius: float, wake_hour: float, draw_obj=None, width_scale=1.0):
        self.wake_hour = wake_hour
        
        # Trigger cache update if needed
        self.get_cached_energy(0)
        
        # 2. Draw the segments using a reduced visual resolution (e.g., 72 steps)
        steps = 72
        last_px = last_py = None
        for i in range(steps + 1):
            h = (i / float(steps)) * 24.0
            energy = self.get_cached_energy(h)
            current_r = self.get_display_radius(energy, radius)

            angle = (18.0 - h) * 15.0
            rad   = math.radians(angle)
            px = cx + current_r * math.cos(rad)
            py = cy - current_r * math.sin(rad)
            
            if last_px is not None:
                color = self.interpolate_color(energy)
                width = int(4 * width_scale)
                if draw_obj:
                    draw_obj.line([(last_px, last_py), (px, py)], fill=color, width=width)
                else:
                    self.canvas.create_line(
                        last_px, last_py, px, py,
                        fill=color,
                        width=width,
                        capstyle=tk.ROUND,
                        tags="energy_curve",
                    )
            last_px, last_py = px, py

    def _draw_indicator(self, cx, cy, radius, hour, color):
        angle = (18 - hour) * 15
        rad = math.radians(angle)

        triangle_size = radius * 0.08
        base_width = triangle_size * 0.8

        tip_r = radius - triangle_size
        base_r = radius

        # Tip point
        tx = cx + tip_r * math.cos(rad)
        ty = cy - tip_r * math.sin(rad)

        # Base points (perpendicular to the radial line)
        # rad + pi/2 gives the direction of the base line
        perp_rad = rad + math.pi/2
        dx = math.cos(perp_rad) * (base_width / 2)
        dy = -math.sin(perp_rad) * (base_width / 2)

        bx = cx + base_r * math.cos(rad)
        by = cy - base_r * math.sin(rad)

        x1, y1 = bx + dx, by + dy
        x2, y2 = bx - dx, by - dy

        self.canvas.create_polygon(
            tx, ty, x1, y1, x2, y2,
            fill=color, outline="", tags="energy_indicators"
        )

    def draw_bathyphase_indicator(self, cx, cy, radius, bathy_hour, energy_val=None):
        if bathy_hour is None: return
        color = self.interpolate_color(energy_val) if energy_val is not None else "#1E6363"
        self._draw_indicator(cx, cy, radius, bathy_hour, color)

    def draw_acrophase_indicator(self, cx, cy, radius, acro_hour, energy_val):
        if acro_hour is None: return
        color = self.interpolate_color(energy_val)
        self._draw_indicator(cx, cy, radius, acro_hour, color)
