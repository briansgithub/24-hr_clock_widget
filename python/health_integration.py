"""Atomic provider cutover and fast sleep publication for the desktop clock."""
from dataclasses import asdict
from datetime import datetime, timedelta
from pathlib import Path
import json
import shutil
import threading
import time

from health_contract import Complete, Pending, Failed, Category, HealthError, SleepStatus, reconcile
from google_health_client import DesktopCredentials, GoogleHealthClient, atomic_json
from energy_logic import compute_sleep_debt, find_bathyphase


def energy_inputs(logs, goal=9.75, include_naps=True, excluded_dates=None, bathy=None):
    mains = [s for s in logs if s.get('isMainSleep')]
    efficiency = sum(s['minutesAsleep'] / s['timeInBed'] if s.get('timeInBed', 0) > 0 else .92 for s in mains) / len(mains) if mains else .92
    need = goal * efficiency
    today = datetime.now().date().isoformat()
    available = [s for s in logs if s['dateOfSleep'] == today]
    if not available and logs:
        latest = max(s['dateOfSleep'] for s in logs)
        available = [s for s in logs if s['dateOfSleep'] == latest]
    main = next((s for s in available if s.get('isMainSleep')), None)
    primary = main or max(available, key=lambda s: s['endTime'], default=None)
    def hour(key):
        if not primary: return None
        value = datetime.fromisoformat(primary[key])
        return value.hour + value.minute / 60 + value.second / 3600
    return dict(wake_hour=hour('endTime'), sleep_hour=hour('startTime'),
        sleep_duration=(sum(s['minutesAsleep'] for s in available) if include_naps else primary['minutesAsleep']) / 60 if primary else None,
        sleep_debt_hours=compute_sleep_debt(logs, need, include_naps, excluded_dates or []),
        bathyphase_hour=bathy, active_sleep_date=primary['dateOfSleep'] if primary else None,
        raw_sleep_logs=logs, raw_hr_points=[], empirical_efficiency=efficiency, sleep_need_hours=need,
        is_real_today=bool(primary and primary['dateOfSleep'] == today), from_cache=True)


class HealthIntegration:
    def __init__(self, legacy, directory=None, now=time.time, sleeper=time.sleep, google=None):
        self.legacy = legacy
        self.directory = Path(directory or Path(__file__).parent)
        self.path = self.directory / 'health_state.json'
        self.now, self.sleeper = now, sleeper
        self.lock = threading.RLock()
        try:
            self.state = json.loads(self.path.read_text())
        except FileNotFoundError:
            self.state = {'active_provider': 'fitbit', 'logs': [], 'status': {}}
        except (ValueError, OSError):
            # Corruption must not silently switch a Google installation back to Fitbit.
            raise HealthError(Category.CONFIGURATION) from None
        expected = self.state.get('legacy_user_id') or (legacy.tokens or {}).get('user_id', '')
        self.google = google or GoogleHealthClient(DesktopCredentials(self.directory), expected)
        self.status = SleepStatus(**self.state.get('status', {}))
        self.bathy = self.state.get('bathyphase')

    @property
    def provider(self): return self.state['active_provider']

    def _save(self):
        self.state['status'] = asdict(self.status)
        atomic_json(self.path, self.state)

    def authorize(self, use_existing=False):
        with self.lock:
            credentials = None if use_existing else self.google.credentials.authorize()
            class StagedCredential:
                def access_token(self, rejected=None):
                    if rejected is not None: raise HealthError(Category.AUTH_REQUIRED)
                    return credentials.token
            candidate = GoogleHealthClient(self.google.credentials if use_existing else StagedCredential(), self.google.expected_legacy_id)
            identity = candidate.identity()
            end = datetime.now().date()
            start = end - timedelta(days=14)
            result = candidate.fetch_sleep(start.isoformat(), end.isoformat())
            # Save staging output separately; failed/processing fetch cannot replace the active cache.
            if isinstance(result, (Complete, Pending)):
                atomic_json(self.directory / 'google_health_staging.json', {'logs': result.logs, 'identity': identity, 'complete': isinstance(result, Complete)})
            if not isinstance(result, Complete):
                if isinstance(result, Failed): raise HealthError(result.category, result.retry_at)
                raise HealthError(Category.SERVER, self.now() + result.retry_after)
            for filename in ('fitbit_summary.json', 'fitbit_sleep_logs.json', 'fitbit_hr_intraday.json'):
                source = self.directory / filename
                backup = self.directory / ('legacy_' + filename)
                if source.exists() and not backup.exists(): shutil.copy2(source, backup)
            now = self.now()
            self.state = dict(active_provider='google_health', legacy_user_id=identity['legacyUserId'],
                health_user_id=identity['healthUserId'], logs=reconcile([], result.logs, start.isoformat(), end.isoformat(), True))
            self.status = SleepStatus(last_attempt=now, last_verified=now, last_changed=now, phase='CURRENT')
            if credentials is not None:
                self.google.credentials.commit(credentials)
            self._save()

    def _record(self, result, start, end):
        now = self.now()
        old = self.state.get('logs', [])
        if isinstance(result, (Complete, Pending)):
            self.state['logs'] = reconcile(old, result.logs, start, end, isinstance(result, Complete))
            if old != self.state['logs']: self.status.last_changed = now
        self.status.error = None
        if isinstance(result, Complete):
            self.status.last_verified, self.status.phase = now, 'CURRENT'
            self.status.next_retry = self.status.server_retry = self.status.retry_count = 0
        elif isinstance(result, Pending):
            self.status.phase = 'PROCESSING'
            self.status.server_retry = now + result.retry_after
            self.status.next_retry = self.status.server_retry
        else:
            self.status.phase, self.status.error = 'ERROR', result.category.value
            self.status.server_retry = result.retry_at
            self.status.next_retry = 0 if result.category in (Category.AUTH_REQUIRED, Category.CONFIGURATION) else max(result.retry_at, now + min(900, 30 * 2 ** min(self.status.retry_count, 5)))
            self.status.retry_count += 1
        self._save()

    def get_all_energy_inputs(self, bedtime_goal_hours=9.75, include_naps=True, force=False,
                              excluded_dates=None, on_sleep=None, history=False):
        if self.provider == 'fitbit':
            return self.legacy.get_all_energy_inputs(bedtime_goal_hours=bedtime_goal_hours, include_naps=include_naps,
                force=force, excluded_dates=excluded_dates)
        with self.lock:
            today = datetime.now().date()
            now = self.now()
            full = force or history or now - self.state.get('history_checked', 0) >= 900
            start, end = (today - timedelta(days=14 if full else 1)).isoformat(), today.isoformat()
            def publish():
                value = energy_inputs(self.state.get('logs', []), bedtime_goal_hours, include_naps, excluded_dates, self.bathy)
                value['health_status'] = asdict(self.status)
                value['provider'] = self.provider
                value['raw_hr_points'] = self.state.get('heart_points', [])
                value['from_cache'] = self.status.phase != 'CURRENT'
                if on_sleep: on_sleep(value)
                return value
            permitted = max(self.status.server_retry, 0 if force else self.status.next_retry)
            if permitted > now or (not force and now - self.status.last_attempt < 30):
                return publish()
            burst_end = now + 60
            while True:
                self.status.last_attempt, self.status.phase = self.now(), 'CHECKING'
                self._save()
                result = self.google.fetch_sleep(start, end)
                self._record(result, start, end)
                inputs = publish()  # UI receives persisted sleep before any heart request or backup.
                if isinstance(result, Pending):
                    if self.now() + result.retry_after <= burst_end:
                        self.sleeper(result.retry_after)
                        continue
                    step = min(self.status.retry_count, 2)
                    self.status.next_retry = self.now() + max(result.retry_after, (60, 300, 900)[step])
                    self.status.retry_count += 1
                    self._save()
                    inputs['health_status'] = asdict(self.status)
                elif isinstance(result, Complete) and full:
                    self.state['history_checked'] = self.now()
                    self._save()
                break
            if isinstance(result, Complete):
                if force or self.now() - self.state.get('metrics_checked', 0) >= 900:
                    mains = [s for s in self.state['logs'] if s['isMainSleep']]
                    main = max(mains, key=lambda s: s['endInstant'], default=None)
                    if main:
                        try:
                            points = self.google.heart_rate(main['startInstant'], main['endInstant'])
                            self.bathy = find_bathyphase(points) if points else None
                            self.state.update(bathyphase=self.bathy, metrics_checked=self.now(), metrics_error=None,
                                              heart_points=points, heart_date=main['dateOfSleep'])
                            inputs['bathyphase_hour'], inputs['raw_hr_points'] = self.bathy, points
                            atomic_json(self.directory / 'fitbit_hr_intraday.json', {
                                'date': main['dateOfSleep'], 'hr_points': points, 'provider': self.provider})
                        except HealthError as error:
                            self.state['metrics_error'] = error.category.value
                        self._save()
                # Compatibility outputs for existing analysis tools; native state remains authoritative.
                atomic_json(self.directory / 'fitbit_sleep_logs.json', {'date': end, 'sleep_logs': self.state['logs'],
                    'timestamp': self.status.last_verified, 'is_real_today': inputs['is_real_today'], 'provider': self.provider})
                atomic_json(self.directory / 'fitbit_summary.json', {'date': end, 'inputs': inputs, 'is_real_today': inputs['is_real_today'], 'provider': self.provider})
            return inputs
