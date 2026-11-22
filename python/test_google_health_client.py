import unittest
from health_contract import Complete, Failed, Category
from google_health_client import GoogleHealthClient, SOURCE
from test_health_contract import session


class Response:
    def __init__(self, value, status=200, headers=None):
        self.value, self.status_code, self.headers = value, status, headers or {}
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def json(self): return self.value


class Transport:
    def __init__(self, responses):
        self.responses, self.calls = list(responses), []
    def get(self, url, **kwargs):
        self.calls.append((url, dict(kwargs.get('params') or {}), kwargs['headers'].copy()))
        return self.responses.pop(0)


class Credentials:
    def __init__(self): self.renewals = 0
    def access_token(self, rejected=None):
        if rejected is not None: self.renewals += 1
        return 'renewed' if self.renewals else 'original'


class GoogleHealthTests(unittest.TestCase):
    def client(self, responses):
        transport, credentials = Transport(responses), Credentials()
        return GoogleHealthClient(credentials, 'owner', session=transport, now=lambda: 100), transport, credentials

    def test_all_pages_and_source(self):
        client, transport, _ = self.client([
            Response({'dataPoints': [session(str(i)) for i in range(25)], 'nextPageToken': 'next'}),
            Response({'dataPoints': [session('26')]})])
        result = client.fetch_sleep('2026-09-01', '2026-09-15')
        self.assertIsInstance(result, Complete)
        self.assertEqual(len(result.logs), 26)
        self.assertEqual(transport.calls[1][1]['pageToken'], 'next')
        self.assertEqual(transport.calls[0][1]['dataSourceFamily'], SOURCE)
        self.assertIn('2026-09-16', transport.calls[0][1]['filter'])

    def test_later_page_failure_is_not_authoritative(self):
        client, _, _ = self.client([Response({'dataPoints': [session()], 'nextPageToken': 'next'}), Response({}, 503)])
        result = client.fetch_sleep('2026-09-01', '2026-09-15')
        self.assertEqual(result, Failed(Category.SERVER))

    def test_empty_protobuf_response_is_complete(self):
        client, _, _ = self.client([Response({})])
        self.assertEqual(client.fetch_sleep('2026-09-01', '2026-09-15'), Complete([]))

    def test_401_retries_once(self):
        client, transport, credentials = self.client([Response({}, 401), Response({})])
        self.assertIsInstance(client.fetch_sleep('2026-09-01', '2026-09-15'), Complete)
        self.assertEqual(credentials.renewals, 1)
        self.assertEqual(transport.calls[1][2]['Authorization'], 'Bearer renewed')

    def test_rate_deadline_blocks_repeated_call(self):
        client, transport, credentials = self.client([Response({}, 429, {'Retry-After': '300'})])
        self.assertEqual(client.fetch_sleep('2026-09-01', '2026-09-15'), Failed(Category.RATE_LIMIT, 400))
        self.assertEqual(client.fetch_sleep('2026-09-01', '2026-09-15'), Failed(Category.RATE_LIMIT, 400))
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(credentials.renewals, 0)

    def test_repeated_pagination_token_fails(self):
        client, _, _ = self.client([Response({'nextPageToken': 'same'}), Response({'nextPageToken': 'same'})])
        self.assertEqual(client.fetch_sleep('2026-09-01', '2026-09-15'), Failed(Category.DECODING))

    def test_api_disabled_does_not_request_consent(self):
        client, _, _ = self.client([Response({'error': {'details': [{'reason': 'SERVICE_DISABLED'}]}}, 403)])
        self.assertEqual(client.fetch_sleep('2026-09-01', '2026-09-15'), Failed(Category.CONFIGURATION))

    def test_identity_requires_existing_owner(self):
        client, _, _ = self.client([Response({'healthUserId': 'health', 'legacyUserId': 'different'})])
        with self.assertRaisesRegex(Exception, 'CONFIGURATION'):
            client.identity()


class DesktopCredentialConcurrencyTests(unittest.TestCase):
    @unittest.skipUnless(__import__('os').name == 'nt', 'Windows credential lock')
    def test_overlapping_callers_reuse_one_refresh(self):
        import tempfile
        import time
        from concurrent.futures import ThreadPoolExecutor
        from types import SimpleNamespace
        from unittest.mock import Mock
        from google_health_client import DesktopCredentials
        with tempfile.TemporaryDirectory() as directory:
            credentials = SimpleNamespace(valid=False, token='expired')
            def refresh(_request):
                time.sleep(.02)
                credentials.valid, credentials.token = True, 'renewed'
            credentials.refresh = Mock(side_effect=refresh)
            vault = DesktopCredentials(directory)
            vault._load = lambda: credentials
            vault._save = Mock()
            with ThreadPoolExecutor(max_workers=8) as workers:
                tokens = list(workers.map(lambda _: vault.access_token(), range(8)))
            self.assertEqual(tokens, ['renewed'] * 8)
            self.assertEqual(credentials.refresh.call_count, 1)
            vault._save.assert_called_once()


if __name__ == '__main__': unittest.main()
