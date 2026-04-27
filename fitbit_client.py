import webbrowser
import json
import os
import base64
import hashlib
import secrets
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime

class FitbitClient:
    def __init__(self, client_id, client_secret, token_file='fitbit_tokens.json'):
        self.client_id = client_id
        self.client_secret = client_secret
        self.token_file = token_file
        self.redirect_uri = 'http://localhost:8080'
        self.tokens = self._load_tokens()

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
            'client_id': self.client_id,
            'response_type': 'code',
            'scope': 'sleep',
            'redirect_uri': self.redirect_uri,
            'expires_in': '604800' # 1 week
        }
        return f"https://www.fitbit.com/oauth2/authorize?{urllib.parse.urlencode(params)}"

    def authorize(self):
        print("Opening browser for Fitbit authorization...")
        webbrowser.open(self.get_auth_url())
        
        # Start a temporary server to catch the code
        code = None
        class AuthHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                nonlocal code
                query = urllib.parse.urlparse(self.path).query
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

        server = HTTPServer(('localhost', 8080), AuthHandler)
        while not code:
            server.handle_request()
        server.server_close()
        
        return self._fetch_tokens(code)

    def _fetch_tokens(self, code):
        auth_header = base64.b64encode(f"{self.client_id}:{self.client_secret}".encode()).decode()
        data = {
            'client_id': self.client_id,
            'grant_type': 'authorization_code',
            'redirect_uri': self.redirect_uri,
            'code': code
        }
        req = urllib.request.Request(
            'https://api.fitbit.com/oauth2/token',
            data=urllib.parse.urlencode(data).encode(),
            headers={'Authorization': f'Basic {auth_header}', 'Content-Type': 'application/x-www-form-urlencoded'}
        )
        with urllib.request.urlopen(req) as resp:
            tokens = json.loads(resp.read().decode())
            self._save_tokens(tokens)
            return tokens

    def refresh_tokens(self):
        if not self.tokens: return None
        auth_header = base64.b64encode(f"{self.client_id}:{self.client_secret}".encode()).decode()
        data = {
            'grant_type': 'refresh_token',
            'refresh_token': self.tokens['refresh_token']
        }
        req = urllib.request.Request(
            'https://api.fitbit.com/oauth2/token',
            data=urllib.parse.urlencode(data).encode(),
            headers={'Authorization': f'Basic {auth_header}', 'Content-Type': 'application/x-www-form-urlencoded'}
        )
        try:
            with urllib.request.urlopen(req) as resp:
                tokens = json.loads(resp.read().decode())
                self._save_tokens(tokens)
                return tokens
        except Exception as e:
            print(f"Token refresh failed: {e}")
            return None

    def get_sleep_data(self):
        if not self.tokens:
            self.authorize()
            
        date_str = datetime.now().strftime("%Y-%m-%d")
        url = f"https://api.fitbit.com/1.2/user/-/sleep/date/{date_str}.json"
        
        try:
            return self._make_request(url)
        except Exception as e:
            # Try refreshing once if it fails (likely expired token)
            print("Request failed, trying token refresh...")
            if self.refresh_tokens():
                return self._make_request(url)
            raise e

    def _make_request(self, url):
        req = urllib.request.Request(
            url,
            headers={'Authorization': f"Bearer {self.tokens['access_token']}"}
        )
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())

    def get_main_sleep_times(self):
        cache_file = 'fitbit_sleep_cache.json'
        today = datetime.now().strftime("%Y-%m-%d")
        
        # Try to load from cache first
        if os.path.exists(cache_file):
            try:
                with open(cache_file, 'r') as f:
                    cache_data = json.load(f)
                    if cache_data.get('date') == today:
                        return cache_data.get('sleep_hour'), cache_data.get('wake_hour')
            except Exception:
                pass

        # Fetch from API if cache is missing or outdated
        try:
            json_data = self.get_sleep_data()
            if 'sleep' in json_data and json_data['sleep']:
                # Find main sleep
                main_sleep = next((s for s in json_data['sleep'] if s.get('isMainSleep')), json_data['sleep'][0])
                
                start_dt = datetime.fromisoformat(main_sleep['startTime'].replace('Z', ''))
                end_dt = datetime.fromisoformat(main_sleep['endTime'].replace('Z', ''))
                
                start_hour = start_dt.hour + start_dt.minute / 60.0
                end_hour = end_dt.hour + end_dt.minute / 60.0
                
                # Update cache
                try:
                    with open(cache_file, 'w') as f:
                        json.dump({
                            'date': today,
                            'sleep_hour': start_hour,
                            'wake_hour': end_hour
                        }, f)
                except Exception as e:
                    print(f"Failed to save sleep cache: {e}")
                
                return start_hour, end_hour
        except Exception as e:
            print(f"Error getting Fitbit sleep times: {e}")
        return None, None
