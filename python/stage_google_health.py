"""Authorize and validate Google Health without switching the running clock."""
import argparse
from datetime import datetime, timedelta

from google_health_client import DesktopCredentials, GoogleHealthClient, atomic_json
from health_contract import Complete, HealthError, Category


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-account', required=True)
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--activate-verified', action='store_true',
                        help='Use saved credentials and activate after a fresh identity/full-history check; run only after comparing staging.')
    args = parser.parse_args()
    if args.activate_verified:
        from types import SimpleNamespace
        from health_integration import HealthIntegration
        connection = HealthIntegration(SimpleNamespace(tokens={'user_id': args.legacy_account}))
        connection.authorize(use_existing=True)
        print('Google Health activated after fresh identity and complete-history verification. Legacy caches retained.')
        return
    vault = DesktopCredentials()
    credentials = vault.authorize(open_browser=not args.no_browser, timeout=args.timeout)

    class Candidate:
        def access_token(self, rejected=None):
            if rejected is not None:
                raise HealthError(Category.AUTH_REQUIRED)
            return credentials.token

    provider = GoogleHealthClient(Candidate(), args.legacy_account)
    identity = provider.identity()
    end = datetime.now().date()
    result = provider.fetch_sleep((end - timedelta(days=14)).isoformat(), end.isoformat())
    if not isinstance(result, Complete):
        raise HealthError(getattr(result, 'category', Category.SERVER))
    atomic_json(vault.directory / 'google_health_staging.json',
                {'identity': identity, 'logs': result.logs, 'complete': True})
    vault.commit(credentials)
    print(f'Google Health verified: {len(result.logs)} staged sleep records. Active provider unchanged.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except HealthError as error:
        print(f'Google Health staging stopped: {error.category.value}. Cached sleep unchanged.')
        raise SystemExit(1)
    except Exception:
        # OAuth errors may embed callback codes; never print their raw text.
        print('Google Health authorization was canceled, timed out, or unavailable. Cached sleep unchanged.')
        raise SystemExit(1)
