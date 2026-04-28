import webbrowser
import json
import os
import base64
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime, timedelta


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
            self.authorize()
        try:
            return self._make_request(url)
        except Exception:
            print(f"[FitbitClient] Request failed — attempting token refresh...")
            if self.refresh_tokens():
                return self._make_request(url)
            raise

    # ─────────────────────────────────────────────────────────────────────────
    # SLEEP  (scope: sleep)
    # ─────────────────────────────────────────────────────────────────────────

    def get_sleep_range(self, start_date: str, end_date: str) -> list[dict]:
        """
        Returns main-sleep records for [start_date, end_date] (inclusive).
        Date format: 'YYYY-MM-DD'.

        Used for:
          • wake_hour / sleep_duration  (today's record)
          • 14-day rolling sleep debt   (pass to compute_sleep_debt())
        """
        url  = f"https://api.fitbit.com/1.2/user/-/sleep/date/{start_date}/{end_date}.json"
        data = self._request_with_refresh(url)

        print(f"\n[get_sleep_range] {start_date} -> {end_date}")
        print(f"  total records returned : {len(data.get('sleep', []))}")

        main_sleep = [s for s in data.get('sleep', []) if s.get('isMainSleep')]
        print(f"  main-sleep records     : {len(main_sleep)}")

        for s in main_sleep:
            mins   = s.get('minutesAsleep', 0)
            stages = s.get('levels', {}).get('summary', {})
            print(f"  {s.get('dateOfSleep')}  "
                  f"slept={mins}min ({mins/60:.2f}h)  "
                  f"deep={stages.get('deep',{}).get('minutes','?')}min  "
                  f"rem={stages.get('rem',{}).get('minutes','?')}min  "
                  f"start={s.get('startTime','?')}  "
                  f"end={s.get('endTime','?')}")

        return main_sleep


    # ─────────────────────────────────────────────────────────────────────────
    # INTRADAY HEART RATE  (scope: heartrate — requires Personal app type)
    # ─────────────────────────────────────────────────────────────────────────

    def get_intraday_hr_during_sleep(
        self,
        sleep_record: dict,
    ) -> list[dict]:
        """
        Fetches 1-minute intraday HR and trims it to the sleep window defined
        by sleep_record['startTime'] and sleep_record['endTime'].

        Returns a list of {"time": "HH:MM:SS", "value": <bpm>} dicts —
        ready to pass directly to find_bathyphase().

        Requires:
          • 'heartrate' scope
          • app registered as type 'Personal' on dev.fitbit.com
        """
        if not sleep_record:
            print("[get_intraday_hr] WARNING: no sleep record provided — returning []")
            return []

        start_dt = datetime.fromisoformat(sleep_record['startTime'].replace('Z', ''))
        end_dt   = datetime.fromisoformat(sleep_record['endTime'].replace('Z', ''))

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
        sleep_need_hours: float = 8.0,
        debt_window_days: int   = 14,
    ) -> dict:
        """
        Fetches all data required by energy_logic.get_energy_level() and
        returns it as a single dict.

        Returns:
            {
                'wake_hour':        float,        # clock hour of wake-up
                'sleep_hour':       float,        # clock hour of getting in bed (from startTime)
                'sleep_duration':   float,        # last night's sleep in hours
                'sleep_debt_hours': float,        # rolling debt over window
                'bathyphase_hour':  float | None, # lowest-HR clock hour
                'raw_sleep_logs':   list[dict],   # for compute_sleep_debt()
                'raw_hr_points':    list[dict],   # for find_bathyphase()
            }
        """
        # -- 0. Check Cache --------------------------------------------------
        summary_cache = 'fitbit_summary.json'
        sleep_cache   = 'fitbit_sleep_logs.json'
        hr_cache      = 'fitbit_hr_intraday.json'
        today         = datetime.now().date()
        today_str     = today.strftime("%Y-%m-%d")

        if os.path.exists(summary_cache):
            try:
                # Cleanup old monolithic cache if it still exists
                old_cache = 'fitbit_sleep_cache.json'
                if os.path.exists(old_cache):
                    try: os.remove(old_cache)
                    except: pass

                with open(summary_cache, 'r') as f:
                    cached = json.load(f)
                if cached.get('date') == today_str and 'inputs' in cached:
                    print(f"[get_all_energy_inputs] Using cached energy inputs for {today_str}")
                    # Ensure returned dict has empty raw lists for consistency
                    res = cached['inputs']
                    res.setdefault('raw_sleep_logs', [])
                    res.setdefault('raw_hr_points', [])
                    return res
            except Exception:
                pass

        print(f"\n{'='*55}")
        print(f"[get_all_energy_inputs] Fetching all Fitbit data...")
        print(f"  sleep_need_hours = {sleep_need_hours}  |  debt_window = {debt_window_days} days")
        print(f"{'='*55}")

        start_date = (today - timedelta(days=debt_window_days - 1)).strftime("%Y-%m-%d")
        end_date   = today_str

        # -- 1. 14-day sleep log -------------------------------------------
        sleep_logs = self.get_sleep_range(start_date, end_date)

        # -- 2. Parse today's record ---------------------------------------
        today_record = next(
            (s for s in sleep_logs if s.get('dateOfSleep') == today_str),
            None
        )

        # Fallback: use the most recent record available if today's is missing
        if not today_record and sleep_logs:
            today_record = max(sleep_logs, key=lambda s: s.get('dateOfSleep', ''))
            print(f"[get_all_energy_inputs] No record for {today_str}, using latest: {today_record.get('dateOfSleep')}")

        wake_hour      = None
        sleep_hour     = None
        sleep_duration = None

        if today_record:
            start_dt = datetime.fromisoformat(today_record['startTime'].replace('Z', ''))
            end_dt   = datetime.fromisoformat(today_record['endTime'].replace('Z', ''))
            sleep_hour = start_dt.hour + start_dt.minute / 60.0 + start_dt.second / 3600.0
            wake_hour = end_dt.hour + end_dt.minute / 60.0 + end_dt.second / 3600.0
            sleep_duration = today_record['minutesAsleep'] / 60.0
            print(f"\n[get_all_energy_inputs] Today's sleep:")
            print(f"  sleep_hour     = {sleep_hour:.2f}  ({start_dt.strftime('%H:%M')})")
            print(f"  wake_hour      = {wake_hour:.2f}  ({end_dt.strftime('%H:%M')})")
            print(f"  sleep_duration = {sleep_duration:.2f} h")
        else:
            print("\n[get_all_energy_inputs] WARNING: no sleep record for today.")

        # -- 3. Sleep debt -------------------------------------------------
        try:
            from energy_logic import compute_sleep_debt
            sleep_debt = compute_sleep_debt(sleep_logs, sleep_need_hours)
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
            hr_points = self.get_intraday_hr_during_sleep(today_record)
            if hr_points:
                try:
                    from energy_logic import find_bathyphase
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
            'wake_hour':        wake_hour,
            'sleep_hour':       sleep_hour,
            'sleep_duration':   sleep_duration,
            'sleep_debt_hours': sleep_debt,
            'bathyphase_hour':  bathyphase_hour,
        }

        # Save to separate cache files for pragmatic data handling
        try:
            # 1. Summary Cache (Lightweight, used for UI startup)
            with open(summary_cache, 'w') as f:
                json.dump({'date': today_str, 'inputs': inputs}, f)
            
            # 2. Raw Sleep Logs (Medium)
            with open(sleep_cache, 'w') as f:
                json.dump({'date': today_str, 'sleep_logs': sleep_logs}, f)
            
            # 3. Raw Intraday HR (Heavy)
            with open(hr_cache, 'w') as f:
                json.dump({'date': today_str, 'hr_points': hr_points}, f)

            print(f"[get_all_energy_inputs] Saved results to separate cache files for {today_str}")
        except Exception as e:
            print(f"[get_all_energy_inputs] Failed to save cache: {e}")

        # Ensure returned dict has everything expected
        inputs['raw_sleep_logs'] = sleep_logs
        inputs['raw_hr_points']  = hr_points
        
        return inputs
