import webbrowser
import json
import os
import base64
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
import time
from datetime import datetime, timedelta
from energy_logic import compute_sleep_debt, find_bathyphase

class ReauthRequiredError(Exception):
    """Raised when tokens are expired or invalid and require user interaction."""
    pass

class FitbitClient:
    def __init__(self, client_id, client_secret, token_file='fitbit_tokens.json'):
        self.client_id     = client_id
        self.client_secret = client_secret
        self.token_file    = token_file
        self.redirect_uri  = 'http://localhost:8080'
        self.tokens        = self._load_tokens()

    # ─────────────────────────────────────────────────────────────────────────
    # TOKEN MANAGEMENT
    # ─────────────────────────────────────────────────────────────────────────

    def _load_tokens(self):
        if os.path.exists(self.token_file):
            with open(self.token_file, 'r') as f:
                return json.load(f)
        return None

    def _save_tokens(self, tokens):
        self.tokens = tokens
        with open(self.token_file, 'w') as f:
            json.dump(tokens, f)

    def get_auth_url(self):
        params = {
            'client_id':    self.client_id,
            'response_type': 'code',
            # ── FIXED: added heartrate scope ──────────────────────────────
            # 'sleep'       -> 14-day sleep log + sleep stages
            # 'heartrate'   -> intraday 1-min HR (requires Personal app type
            #                 on dev.fitbit.com — no special review needed
            #                 for your own data)
            'scope':        'sleep heartrate',
            'redirect_uri': self.redirect_uri,
            'expires_in':   '604800',
        }
        return f"https://www.fitbit.com/oauth2/authorize?{urllib.parse.urlencode(params)}"

    def authorize(self):
        print("Opening browser for Fitbit authorization...")
        webbrowser.open(self.get_auth_url())

        code = None

        class AuthHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                nonlocal code
                query  = urllib.parse.urlparse(self.path).query
                params = urllib.parse.parse_qs(query)
                if 'code' in params:
                    code = params['code'][0]
                    self.send_response(200)
                    self.send_header('Content-type', 'text/html')
                    self.end_headers()
                    self.wfile.write(b"<h1>Success!</h1><p>You can close this window now.</p>")
                else:
                    self.send_response(400)
                    self.end_headers()

            def log_message(self, *args):
                pass   # suppress per-request console noise

        server = HTTPServer(('localhost', 8080), AuthHandler)
        print("Waiting for OAuth callback on http://localhost:8080 ...")
        while not code:
            server.handle_request()
        server.server_close()

        return self._fetch_tokens(code)

    def _fetch_tokens(self, code):
        auth_header = base64.b64encode(
            f"{self.client_id}:{self.client_secret}".encode()
        ).decode()
        data = {
            'client_id':    self.client_id,
            'grant_type':   'authorization_code',
            'redirect_uri': self.redirect_uri,
            'code':         code,
        }
        req = urllib.request.Request(
            'https://api.fitbit.com/oauth2/token',
            data=urllib.parse.urlencode(data).encode(),
            headers={
                'Authorization': f'Basic {auth_header}',
                'Content-Type':  'application/x-www-form-urlencoded',
            },
        )
        with urllib.request.urlopen(req) as resp:
            tokens = json.loads(resp.read().decode())
            self._save_tokens(tokens)
            print("[FitbitClient] Tokens saved.")
            return tokens

    def refresh_tokens(self):
        if not self.tokens:
            return None
        auth_header = base64.b64encode(
            f"{self.client_id}:{self.client_secret}".encode()
        ).decode()
        data = {
            'grant_type':    'refresh_token',
            'refresh_token': self.tokens['refresh_token'],
        }
        req = urllib.request.Request(
            'https://api.fitbit.com/oauth2/token',
            data=urllib.parse.urlencode(data).encode(),
            headers={
                'Authorization': f'Basic {auth_header}',
                'Content-Type':  'application/x-www-form-urlencoded',
            },
        )
        try:
            with urllib.request.urlopen(req) as resp:
                tokens = json.loads(resp.read().decode())
                self._save_tokens(tokens)
                print("[FitbitClient] Tokens refreshed.")
                return tokens
        except Exception as e:
            print(f"[FitbitClient] Token refresh failed: {e}")
            return None

    def _make_request(self, url):
        """Single API call. Raises on HTTP error."""
        req = urllib.request.Request(
            url,
            headers={'Authorization': f"Bearer {self.tokens['access_token']}"},
        )
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())

    def _request_with_refresh(self, url):
        """API call with one automatic token-refresh retry."""
        if not self.tokens:
            raise ReauthRequiredError("No tokens available")
        try:
            return self._make_request(url)
        except Exception as e:
            err_msg = str(e).lower()
            # If it looks like a token issue (401 or 400), try refresh
            if "401" in err_msg or "400" in err_msg:
                print(f"[FitbitClient] Request failed ({e}) — attempting token refresh...")
                if self.refresh_tokens():
                    try:
                        return self._make_request(url)
                    except Exception as e2:
                        print(f"[FitbitClient] Request still failed after refresh: {e2}")
                
                # If refresh failed or still failing with 401/400, trigger re-auth error
                print("[FitbitClient] Token appears dead — re-authentication required.")
                raise ReauthRequiredError("Token refresh failed or expired")
            
            # For other errors (network, etc.), just raise
            raise

    # ─────────────────────────────────────────────────────────────────────────
    # SLEEP  (scope: sleep)
    # ─────────────────────────────────────────────────────────────────────────

    def get_sleep_range(self, start_date: str, end_date: str, force: bool = False) -> list[dict]:
        """
        Returns main-sleep records for [start_date, end_date] (inclusive).
        Date format: 'YYYY-MM-DD'.
        """
        url  = f"https://api.fitbit.com/1.2/user/-/sleep/date/{start_date}/{end_date}.json"
        
        # Smart Cache: Use local logs if they match today's date
        cache_path = os.path.join(os.path.dirname(__file__), "fitbit_sleep_logs.json")
        if not force and os.path.exists(cache_path):
            try:
                with open(cache_path, 'r') as f:
                    cached = json.load(f)
                last_time   = cached.get('timestamp', 0)
                elapsed_sec = time.time() - last_time
                was_real    = cached.get('is_real_today', False)

                # We use the cache if:
                # A) It matches today's date AND it's "Real" data (already found today's sleep)
                # B) It matches today's date AND it's "Fallback" data but was fetched < 1 hr ago
                if cached.get('date') == end_date:
                    if was_real or elapsed_sec < 3600: # 3600s = 1h
                        label = "Real" if was_real else f"Fallback, {elapsed_sec/60:.1f}m old"
                        print(f"[get_sleep_range] Using cached sleep logs for {end_date} ({label})")
                        return cached.get('sleep_logs', [])
            except: pass

        if force:
            print(f"[get_sleep_range] FORCED Refresh: Fetching fresh sleep logs...")
        else:
            print(f"[get_sleep_range] Fetching fresh sleep logs from Fitbit...")
        data = self._request_with_refresh(url)
        sleep_logs = data.get('sleep', [])

        print(f"\n[get_sleep_range] {start_date} -> {end_date}")
        print(f"  total records returned : {len(sleep_logs)}")

        main_sleeps = [s for s in sleep_logs if s.get('isMainSleep')]
        naps        = [s for s in sleep_logs if not s.get('isMainSleep')]
        
        print(f"  main-sleep records     : {len(main_sleeps)}")
        print(f"  nap/other records      : {len(naps)}")
        
        for s in sleep_logs:
            label = "MAIN" if s.get('isMainSleep') else "NAP "
            mins = s.get('minutesAsleep', 0)
            hrs  = mins / 60.0
            date = s.get('dateOfSleep')
            start = s.get('startTime', '')
            end   = s.get('endTime', '')
            print(f"  {date} [{label}] slept={mins}min ({hrs:.2f}h) start={start} end={end}")

        return sleep_logs


    # ─────────────────────────────────────────────────────────────────────────
    # INTRADAY HEART RATE  (scope: heartrate — requires Personal app type)
    # ─────────────────────────────────────────────────────────────────────────

    def get_intraday_hr_during_sleep(
        self,
        sleep_record: dict,
        force: bool = False
    ) -> list[dict]:
        """
        Fetches 1-minute intraday HR and trims it to the sleep window defined
        by sleep_record['startTime'] and sleep_record['endTime'].
        """
        if not sleep_record:
            print("[get_intraday_hr] WARNING: no sleep record provided — returning []")
            return []

        start_dt = datetime.fromisoformat(sleep_record['startTime'].replace('Z', ''))
        end_dt   = datetime.fromisoformat(sleep_record['endTime'].replace('Z', ''))
        date_str = sleep_record.get('dateOfSleep')

        # Smart Cache: Use local HR if it matches this specific sleep session date
        cache_path = os.path.join(os.path.dirname(__file__), "fitbit_hr_intraday.json")
        if not force and os.path.exists(cache_path):
            try:
                with open(cache_path, 'r') as f:
                    cached = json.load(f)
                if cached.get('date') == date_str and cached.get('hr_points'):
                    print(f"[get_intraday_hr] Using cached HR for {date_str}")
                    return cached['hr_points']
            except: pass

        # Fitbit intraday endpoint is date-scoped; if sleep spans midnight we
        # need to fetch both dates and merge.
        dates_needed = set()
        cursor = start_dt.date()
        while cursor <= end_dt.date():
            dates_needed.add(cursor.strftime("%Y-%m-%d"))
            cursor += timedelta(days=1)

        print(f"\n[get_intraday_hr] sleep window: {start_dt} -> {end_dt}")
        print(f"  fetching intraday HR for dates: {sorted(dates_needed)}")

        all_points: list[dict] = []
        for date_str in sorted(dates_needed):
            url = (
                f"https://api.fitbit.com/1/user/-/activities/heart"
                f"/date/{date_str}/1d/1min.json"
            )
            try:
                data   = self._request_with_refresh(url)
                points = data.get('activities-heart-intraday', {}).get('dataset', [])
                # Tag each point with its full datetime for filtering
                for p in points:
                    h, m, s = p['time'].split(':')
                    pt_dt = datetime(
                        int(date_str[:4]), int(date_str[5:7]), int(date_str[8:]),
                        int(h), int(m), int(s)
                    )
                    if start_dt <= pt_dt <= end_dt:
                        all_points.append(p)
                print(f"  {date_str}: {len(points)} raw points, "
                      f"{sum(1 for p in points if True)} fetched")
            except Exception as e:
                print(f"  {date_str}: FAILED — {e}")
                print("  -> Is your app registered as type 'Personal' on dev.fitbit.com?")
                print("  -> Is 'heartrate' in your OAuth scope?")

        print(f"  points inside sleep window: {len(all_points)}")
        if all_points:
            values = [p['value'] for p in all_points]
            print(f"  HR range during sleep: {min(values)}–{max(values)} bpm  "
                  f"avg={sum(values)/len(values):.1f} bpm")

        return all_points

    # -------------------------------------------------------------------------
    # CONVENIENCE: fetch everything energy_logic.py needs in one call
    # -------------------------------------------------------------------------

    def get_all_energy_inputs(
        self,
        bedtime_goal_hours: float = 9.75,
        debt_window_days: int   = 14,
        include_naps: bool      = True,
        force: bool             = False,
        excluded_dates: list[str] = None
    ) -> dict:
        """
        Orchestrates fetching:
          1. 14-day sleep range (to calc debt and empirical efficiency)
          2. Today's sleep record (for wake time)
          3. Today's intraday heart rate (for bathyphase)
        """
        if excluded_dates is None:
            excluded_dates = []

        today     = datetime.now()
        today_str = today.strftime("%Y-%m-%d")
        
        summary_cache = os.path.join(os.path.dirname(__file__), "fitbit_summary.json")
        sleep_cache   = os.path.join(os.path.dirname(__file__), "fitbit_sleep_logs.json")
        hr_cache      = os.path.join(os.path.dirname(__file__), "fitbit_hr_intraday.json")

        # -- 1. 14-day sleep log (Fast local read for efficiency and debt) --
        # Fetch 15 days to cover T-0 to T-14
        start_date = (today - timedelta(days=debt_window_days)).strftime("%Y-%m-%d")
        end_date   = today_str
        
        sleep_logs = []
        is_stale_fallback = False
        try:
            sleep_logs = self.get_sleep_range(start_date, end_date, force=force)
        except Exception as e:
            print(f"[get_all_energy_inputs] API fetch failed: {e}. Falling back to cached logs.")
            is_stale_fallback = True
            if os.path.exists(sleep_cache):
                try:
                    with open(sleep_cache, 'r') as f:
                        cached_data = json.load(f)
                        sleep_logs = cached_data.get('sleep_logs', [])
                except: pass
            
            if not sleep_logs:
                raise

        # -- Dynamic Sleep Need Calculation (Average of Ratios, excluding naps) --
        main_sleep_logs = [s for s in sleep_logs if s.get('isMainSleep')]

        if main_sleep_logs:
            effs = []
            for s in main_sleep_logs:
                asleep = s.get('minutesAsleep', 0)
                bed = s.get('timeInBed', s.get('timeIn_bed', 0))
                if bed > 0:
                    effs.append(asleep / bed)
                else:
                    effs.append(0.92)
            empirical_efficiency = sum(effs) / len(effs)
        else:
            # Safety fallback only if NO main sleep data exists in the 14-day window
            print("[get_all_energy_inputs] WARNING: No main sleep logs found to calculate efficiency. Defaulting to 92%.")
            empirical_efficiency = 0.92
        
        sleep_need_hours = bedtime_goal_hours * empirical_efficiency
        
        print(f"[get_all_energy_inputs] Dynamic Efficiency: {empirical_efficiency:.1%}")
        print(f"[get_all_energy_inputs] Dynamic Sleep Need: {sleep_need_hours:.2f}h")

        # -- 2. Check Summary Cache ----------------------------------------
        if os.path.exists(summary_cache):
            try:
                # Cleanup old monolithic cache if it still exists
                old_cache = os.path.join(os.path.dirname(__file__), "fitbit_sleep_cache.json")
                if os.path.exists(old_cache):
                    try: os.remove(old_cache)
                    except: pass

                with open(summary_cache, 'r') as f:
                    cached = json.load(f)

                was_real = cached.get('is_real_today', False)
                cache_mtime = os.path.getmtime(summary_cache)
                elapsed_sec = time.time() - cache_mtime

                # Use the summary cache if it matches today's date AND:
                # A) It's "Real" data (already found today's sleep)
                # B) It's "Fallback" data but was fetched < 1 hr ago
                if not force and cached.get('date') == today_str and 'inputs' in cached:
                    if was_real or elapsed_sec < 3600:
                        print(f"[get_all_energy_inputs] Using cached energy inputs for {today_str} ({'Real' if was_real else 'Fallback'})")
                        res = cached['inputs']

                        # 1. Inject current dynamic values
                        res['empirical_efficiency'] = empirical_efficiency
                        res['sleep_need_hours']     = sleep_need_hours
                        res['include_naps']         = include_naps

                        # 2. Recalculate debt using cached raw logs but current toggle/need/exclusions
                        raw_logs = res.get('raw_sleep_logs', sleep_logs)
                        try:
                            new_debt = compute_sleep_debt(raw_logs, sleep_need_hours, include_naps, excluded_dates)
                            res['sleep_debt_hours'] = new_debt
                        except Exception as e:
                            print(f"[get_all_energy_inputs] Failed to recalculate debt from cache: {e}")

                        res.setdefault('raw_sleep_logs', raw_logs)
                        res.setdefault('raw_hr_points', [])
                        # Ensure active_sleep_date exists (fallback for old caches)
                        if 'active_sleep_date' not in res:
                            if was_real:
                                res['active_sleep_date'] = today_str
                            elif raw_logs:
                                res['active_sleep_date'] = max(s.get('dateOfSleep', '') for s in raw_logs)
                            else:
                                res['active_sleep_date'] = today_str

                        res['from_cache'] = True
                        res['is_real_today'] = was_real
                        return res
                    else:
                        print(f"[get_all_energy_inputs] Summary cache is stale Fallback ({elapsed_sec/60:.1f}m old) — ignoring.")
            except Exception:
                pass

        print(f"\n{'='*55}")
        print(f"[get_all_energy_inputs] Fetching fresh Fitbit data...")
        print(f"  bedtime_goal     = {bedtime_goal_hours}h")
        print(f"  debt_window      = {debt_window_days} days")
        print(f"{'='*55}")


        # -- 2. Parse today's record ---------------------------------------
        # Find all sessions for today
        today_sessions = [s for s in sleep_logs if s.get('dateOfSleep') == today_str]
        
        # Prioritize the main sleep record for today
        today_record = next((s for s in today_sessions if s.get('isMainSleep')), None)
        
        # If no main sleep found for today but there are other sessions (naps), 
        # use the one that ended latest.
        if not today_record and today_sessions:
            today_record = max(today_sessions, key=lambda s: s.get('endTime', ''))
            
        is_real_today = (today_record is not None)

        # Fallback: if absolutely no records for today, use the most recent date available
        if not today_record and sleep_logs:
            latest_date = max(s.get('dateOfSleep', '') for s in sleep_logs)
            latest_sessions = [s for s in sleep_logs if s.get('dateOfSleep') == latest_date]
            # Again, prefer main sleep for that date
            today_record = next((s for s in latest_sessions if s.get('isMainSleep')), latest_sessions[0])
            print(f"[get_all_energy_inputs] No record for {today_str}, using latest date {latest_date}.")

        wake_hour      = None
        sleep_hour     = None
        sleep_duration = None

        if today_record:
            # The 'active' date is the one associated with the record we picked
            active_date = today_record.get('dateOfSleep')
            active_sessions = [s for s in sleep_logs if s.get('dateOfSleep') == active_date]
            
            # Use the main record's start/end for the labels/clock hand logic
            start_dt = datetime.fromisoformat(today_record['startTime'].replace('Z', ''))
            end_dt   = datetime.fromisoformat(today_record['endTime'].replace('Z', ''))
            
            sleep_hour = start_dt.hour + start_dt.minute / 60.0 + start_dt.second / 3600.0
            wake_hour = end_dt.hour + end_dt.minute / 60.0 + end_dt.second / 3600.0
            
            # Sum duration from ALL sessions on this date (if naps included)
            if include_naps:
                sleep_duration = sum(s['minutesAsleep'] for s in active_sessions) / 60.0
            else:
                sleep_duration = today_record['minutesAsleep'] / 60.0

            print(f"\n[get_all_energy_inputs] Active sleep date: {active_date}")
            print(f"  primary_wake_hour = {wake_hour:.2f}  ({end_dt.strftime('%H:%M')})")
            print(f"  total_duration    = {sleep_duration:.2f} h (from {len(active_sessions) if include_naps else 1} sessions)")
        else:
            print("\n[get_all_energy_inputs] WARNING: no sleep record for today.")

        # -- 3. Sleep debt -------------------------------------------------
        try:
            sleep_debt = compute_sleep_debt(sleep_logs, sleep_need_hours, include_naps, excluded_dates)
        except ImportError:
            sleep_debt = sum(
                max(0.0, sleep_need_hours - s['minutesAsleep'] / 60.0)
                for s in sleep_logs[-debt_window_days:]
            )
            print(f"[get_all_energy_inputs] sleep_debt_hours = {sleep_debt:.2f} h (inline calc)")

        # -- 4. Intraday HR -> bathyphase -----------------------------------
        bathyphase_hour = None
        hr_points: list[dict] = []

        if today_record:
            hr_points = self.get_intraday_hr_during_sleep(today_record, force=force)
            if hr_points:
                try:
                    bathyphase_hour = find_bathyphase(hr_points)
                except ImportError:
                    buckets: dict[int, list[float]] = {}
                    for p in hr_points:
                        h = int(p['time'].split(':')[0])
                        buckets.setdefault(h, []).append(float(p['value']))
                    if buckets:
                        avg_by_hour = {h: sum(v)/len(v) for h, v in buckets.items()}
                        bathyphase_hour = float(min(avg_by_hour, key=avg_by_hour.get))
                        print(f"[get_all_energy_inputs] bathyphase_hour = {bathyphase_hour} (inline calc)")

        # -- 5. Summary & Cache --------------------------------------------
        print(f"\n{'-'*55}")
        print(f"[get_all_energy_inputs] FINAL VALUES FOR energy_logic.py")
        print(f"  wake_hour        = {wake_hour}")
        print(f"  sleep_duration   = {sleep_duration}")
        print(f"  sleep_debt_hours = {sleep_debt:.2f} h")
        print(f"  bathyphase_hour  = {bathyphase_hour}")
        print(f"{'-'*55}\n")

        inputs = {
            'wake_hour':            wake_hour,
            'sleep_hour':           sleep_hour,
            'sleep_duration':       sleep_duration,
            'sleep_debt_hours':     sleep_debt,
            'bathyphase_hour':      bathyphase_hour,
            'active_sleep_date':    today_record.get('dateOfSleep') if today_record else None,
            'raw_sleep_logs':       sleep_logs,
            'empirical_efficiency': empirical_efficiency,
            'sleep_need_hours':     sleep_need_hours
        }

        # Save to separate cache files for pragmatic data handling
        # We always cache the logs if we have them, so toggles are instant.
        if sleep_logs:
            try:
                # 1. Summary Cache (Lightweight, used for UI startup)
                with open(summary_cache, 'w') as f:
                    json.dump({'date': today_str, 'inputs': inputs, 'is_real_today': is_real_today}, f)
                
                # 2. Raw Sleep Logs (Medium)
                with open(sleep_cache, 'w') as f:
                    json.dump({
                        'date': today_str, 
                        'sleep_logs': sleep_logs,
                        'timestamp': time.time(),
                        'is_real_today': is_real_today
                    }, f)
                
                # 3. Raw Intraday HR (Heavy) - Only cache if it's real today's data
                if is_real_today and hr_points:
                    with open(hr_cache, 'w') as f:
                        json.dump({'date': today_str, 'hr_points': hr_points}, f)

                print(f"[get_all_energy_inputs] Cached results for {today_str} (is_real_today={is_real_today})")
            except Exception as e:
                print(f"[get_all_energy_inputs] Failed to save cache: {e}")

        inputs['raw_sleep_logs'] = sleep_logs
        inputs['raw_hr_points']  = hr_points
        inputs['empirical_efficiency'] = empirical_efficiency
        inputs['sleep_need_hours']     = sleep_need_hours
        inputs['from_cache'] = is_stale_fallback
        inputs['is_real_today'] = is_real_today and not is_stale_fallback
        
        return inputs
