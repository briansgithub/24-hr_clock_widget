"""Google Health transport and native desktop credentials; no browser on background paths."""
import ctypes
import json
import os
import threading
import time
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Protocol
from email.utils import parsedate_to_datetime

import requests
from health_contract import Category, HealthError, Complete, Pending, Failed, sleep_records, minute_heart_rate

SCOPES = ['https://www.googleapis.com/auth/googlehealth.sleep.readonly',
          'https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly']
SOURCE = 'users/me/dataSourceFamilies/google-sources'
BASE = 'https://health.googleapis.com/v4'


class CredentialProvider(Protocol):
    def access_token(self, rejected=None) -> str: ...


class HealthProvider(Protocol):
    def identity(self) -> dict: ...
    def fetch_sleep(self, start: str, end: str): ...
    def heart_rate(self, start: str, end: str) -> list: ...


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, separators=(',', ':'))
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def _dpapi(value, decrypt=False):
    if os.name != 'nt':
        raise HealthError(Category.CONFIGURATION)  # Never silently store plaintext on other platforms.
    from ctypes import wintypes
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    memory = ctypes.create_string_buffer(value)
    source = Blob(len(value), ctypes.cast(memory, ctypes.POINTER(ctypes.c_ubyte)))
    output = Blob()
    library = ctypes.WinDLL('crypt32', use_last_error=True)
    operation = library.CryptUnprotectData if decrypt else library.CryptProtectData
    operation.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob), ctypes.c_void_p,
                          ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    operation.restype = wintypes.BOOL
    if not operation(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise HealthError(Category.CONFIGURATION)
    try:
        return ctypes.string_at(output.data, output.size)
    finally:
        free = ctypes.WinDLL('kernel32', use_last_error=True).LocalFree
        free.argtypes, free.restype = [ctypes.c_void_p], ctypes.c_void_p
        free(output.data)


class DesktopCredentials:
    _lock = threading.RLock()

    def __init__(self, directory=None, now=time.time):
        self.directory = Path(directory or Path(__file__).parent)
        self.path = self.directory / 'google_health_credentials.dpapi'
        self.config_path = self.directory / 'google_health_client.json'
        self.now = now

    @contextmanager
    def _guard(self):
        with self._lock:
            if os.name != 'nt':
                raise HealthError(Category.CONFIGURATION)
            import msvcrt
            with self.path.with_suffix('.lock').open('a+b') as stream:
                if stream.tell() == 0:
                    stream.write(b'0')
                    stream.flush()
                stream.seek(0)
                try:
                    msvcrt.locking(stream.fileno(), msvcrt.LK_LOCK, 1)
                except OSError:
                    raise HealthError(Category.NETWORK) from None
                try:
                    yield
                finally:
                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)

    def _load(self):
        if not self.path.exists():
            raise HealthError(Category.AUTH_REQUIRED)
        try:
            from google.oauth2.credentials import Credentials
            info = json.loads(_dpapi(self.path.read_bytes(), decrypt=True))
            return Credentials.from_authorized_user_info(info, SCOPES)
        except HealthError:
            raise
        except Exception:
            raise HealthError(Category.CONFIGURATION) from None

    def _save(self, credentials):
        # JSON exists in memory only; the temporary file is encrypted too.
        value = _dpapi(credentials.to_json().encode())
        temporary = self.path.with_suffix('.tmp')
        with temporary.open('wb') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path)

    def access_token(self, rejected=None):
        with self._guard():
            credentials = self._load()
            if credentials.valid and (rejected is None or credentials.token != rejected):
                return credentials.token
            try:
                from google.auth.transport.requests import Request
                credentials.refresh(Request())
            except Exception as error:
                from google.auth.exceptions import RefreshError, TransportError
                if isinstance(error, TransportError):
                    raise HealthError(Category.NETWORK) from None
                detail = error.args[1] if len(error.args) > 1 and isinstance(error.args[1], dict) else {}
                code = detail.get('error')
                if code in ('invalid_grant', 'invalid_token'):
                    raise HealthError(Category.AUTH_REQUIRED) from None
                if getattr(error, 'retryable', False):
                    raise HealthError(Category.SERVER) from None
                raise HealthError(Category.CONFIGURATION) from None
            self._save(credentials)
            return credentials.token

    def authorize(self, open_browser=True, timeout=600):
        from google_auth_oauthlib.flow import InstalledAppFlow
        if not self.config_path.exists():
            raise HealthError(Category.CONFIGURATION)
        with self._lock:
            flow = InstalledAppFlow.from_client_secrets_file(str(self.config_path), SCOPES, autogenerate_code_verifier=True)
            options = {'access_type': 'offline'}
            # A reconnect may need a replacement refresh grant, but ordinary refresh never prompts.
            options['prompt'] = 'consent'
            credentials = flow.run_local_server(host='127.0.0.1', port=0, timeout_seconds=timeout,
                open_browser=open_browser, authorization_prompt_message='Complete Google Health authorization in your browser.' if open_browser else 'AUTHORIZATION_URL {url}',
                success_message='Authorization received. You may return to the clock.',
                **options)
            if not credentials.refresh_token or not set(SCOPES).issubset(set(credentials.granted_scopes or credentials.scopes or [])):
                raise HealthError(Category.AUTH_REQUIRED)
            return credentials  # Caller verifies account and stages sleep before replacing credentials.

    def commit(self, credentials):
        with self._guard():
            self._save(credentials)


class GoogleHealthClient:
    def __init__(self, credentials, expected_legacy_id, session=None, now=time.time):
        self.credentials, self.expected_legacy_id = credentials, expected_legacy_id
        self.session = session or requests.Session()
        self.now, self.retry_at = now, 0

    def _request(self, path, params=None):
        if self.retry_at > self.now():
            raise HealthError(Category.RATE_LIMIT, self.retry_at)
        token = self.credentials.access_token()
        for attempt in range(2):
            try:
                with self.session.get(BASE + path, params=params, headers={'Authorization': 'Bearer ' + token}, timeout=(10, 20)) as response:
                    status = response.status_code
                    if status == 401 and attempt == 0:
                        token = self.credentials.access_token(rejected=token)
                        continue
                    if status == 429:
                        header = response.headers.get('Retry-After', '60')
                        try:
                            deadline = self.now() + max(1, float(header))
                        except ValueError:
                            try:
                                deadline = max(self.now() + 1, parsedate_to_datetime(header).timestamp())
                            except Exception:
                                deadline = self.now() + 60
                        self.retry_at = deadline
                        raise HealthError(Category.RATE_LIMIT, deadline)
                    if status >= 500:
                        raise HealthError(Category.SERVER)
                    if status == 401:
                        raise HealthError(Category.AUTH_REQUIRED)
                    if status == 403:
                        # Disabled API, quotas, or project policy are not evidence of revoked consent.
                        try:
                            details = response.json().get('error', {}).get('details', [])
                            missing = any(d.get('reason') == 'ACCESS_TOKEN_SCOPE_INSUFFICIENT' for d in details)
                        except ValueError:
                            missing = False
                        raise HealthError(Category.AUTH_REQUIRED if missing else Category.CONFIGURATION)
                    if not 200 <= status < 300:
                        raise HealthError(Category.CONFIGURATION)
                    try:
                        value = response.json()
                        if not isinstance(value, dict):
                            raise ValueError()
                        return value
                    except ValueError:
                        raise HealthError(Category.DECODING) from None
            except requests.RequestException:
                raise HealthError(Category.NETWORK) from None
        raise HealthError(Category.AUTH_REQUIRED)

    def identity(self):
        value = self._request('/users/me/identity')
        if not value.get('healthUserId') or not self.expected_legacy_id or value.get('legacyUserId') != self.expected_legacy_id:
            raise HealthError(Category.CONFIGURATION)
        return value

    def points(self, data_type, field, start, end):
        params = {'dataSourceFamily': SOURCE, 'filter': f'{field} >= "{start}" AND {field} < "{end}"',
                  'pageSize': 25 if data_type == 'sleep' else 10000}
        output, seen = [], set()
        while True:
            value = self._request(f'/users/me/dataTypes/{data_type}/dataPoints:reconcile', params)
            page = value.get('dataPoints', [])  # Protobuf JSON omits empty repeated fields.
            token = value.get('nextPageToken', '')
            if not isinstance(page, list) or not isinstance(token, str):
                raise HealthError(Category.DECODING)
            output.extend(page)
            if not token:
                return output
            if token in seen:
                raise HealthError(Category.DECODING)
            seen.add(token)
            params['pageToken'] = token

    def fetch_sleep(self, start, end):
        try:
            upper = (date.fromisoformat(end) + timedelta(days=1)).isoformat()
            return sleep_records(self.points('sleep', 'sleep.interval.civil_end_time', start, upper))
        except HealthError as error:
            return Failed(error.category, error.retry_at)
        except (ValueError, TypeError, KeyError, OverflowError):
            return Failed(Category.DECODING)

    def heart_rate(self, start, end):
        try:
            return minute_heart_rate(self.points('heart-rate', 'heart_rate.sample_time.physical_time', start, end))
        except (ValueError, KeyError, TypeError):
            raise HealthError(Category.DECODING) from None
