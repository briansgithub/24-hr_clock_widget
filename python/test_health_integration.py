import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from health_contract import Complete, Pending, Failed, Category, sleep_records
from health_integration import HealthIntegration
from test_health_contract import session


class HealthIntegrationTests(unittest.TestCase):
    def test_saved_grant_activation_revalidates_history_without_browser(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as directory:
            vault = Mock()
            google = SimpleNamespace(credentials=vault, expected_legacy_id='legacy')
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory, google=google)
            source = Path(directory) / 'fitbit_sleep_logs.json'
            source.write_text('{"sleep_logs":[]}')
            with patch('health_integration.GoogleHealthClient') as transport:
                transport.return_value.identity.return_value = {'legacyUserId': 'legacy', 'healthUserId': 'health'}
                transport.return_value.fetch_sleep.return_value = Complete([])
                integration.authorize(use_existing=True)
                transport.return_value.identity.assert_called_once()
                transport.return_value.fetch_sleep.assert_called_once()
            vault.authorize.assert_not_called()
            vault.commit.assert_not_called()
            self.assertEqual(integration.provider, 'google_health')
            self.assertEqual((Path(directory) / 'legacy_fitbit_sleep_logs.json').read_text(), source.read_text())

    def test_saved_grant_incomplete_history_cannot_switch_provider(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as directory:
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory,
                google=SimpleNamespace(credentials=Mock(), expected_legacy_id='legacy'))
            with patch('health_integration.GoogleHealthClient') as transport:
                transport.return_value.identity.return_value = {'legacyUserId': 'legacy', 'healthUserId': 'health'}
                transport.return_value.fetch_sleep.return_value = Failed(Category.NETWORK)
                with self.assertRaisesRegex(Exception, 'NETWORK'):
                    integration.authorize(use_existing=True)
            self.assertEqual(integration.provider, 'fitbit')

    def test_pending_burst_retains_partial_sleep_and_defers_followup(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = [20000]
            logs = sleep_records([session()]).logs
            google = SimpleNamespace(fetch_sleep=lambda *args: Pending(logs, 30))
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory,
                now=lambda: clock[0], sleeper=lambda delay: clock.__setitem__(0, clock[0] + delay), google=google)
            integration.state['active_provider'] = 'google_health'
            with patch('health_integration.energy_inputs', return_value={'is_real_today': True}):
                result = integration.get_all_energy_inputs(force=True)
            self.assertEqual(clock[0], 20060)
            self.assertEqual(integration.state['logs'], logs)
            self.assertEqual(integration.status.last_verified, 0)
            self.assertEqual(result['health_status']['next_retry'], 20120)

    def test_consent_failure_does_not_schedule_automatic_authorization(self):
        with tempfile.TemporaryDirectory() as directory:
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory, now=lambda: 20000, google=object())
            integration._record(Failed(Category.AUTH_REQUIRED), '2026-09-01', '2026-09-15')
            self.assertEqual(integration.status.next_retry, 0)
            self.assertEqual(integration.status.opacity(20000), .3)

    def test_failures_preserve_cache_and_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = [1000]
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory, now=lambda: clock[0], google=object())
            logs = sleep_records([session()]).logs
            integration._record(Complete(logs), '2026-09-01', '2026-09-15')
            clock[0] += 60
            integration._record(Failed(Category.NETWORK), '2026-09-01', '2026-09-15')
            self.assertEqual(integration.state['logs'], logs)
            self.assertEqual(integration.status.last_verified, 1000)
            self.assertEqual(integration.status.error, 'NETWORK')
            restored = HealthIntegration(SimpleNamespace(tokens={}), directory, google=object())
            self.assertEqual(restored.status.last_verified, 1000)
            self.assertEqual(restored.state['logs'], logs)

    def test_success_unchanged_restores_freshness_and_empty_deletes(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = [1000]
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory, now=lambda: clock[0], google=object())
            logs = sleep_records([session()]).logs
            integration._record(Complete(logs), '2026-09-01', '2026-09-15')
            clock[0] = 20000
            self.assertEqual(integration.status.opacity(clock[0]), .3)
            integration._record(Complete(logs), '2026-09-01', '2026-09-15')
            self.assertEqual(integration.status.last_changed, 1000)
            self.assertEqual(integration.status.opacity(clock[0]), 1)
            integration._record(Complete([]), '2026-09-01', '2026-09-15')
            self.assertEqual(integration.state['logs'], [])

    def test_sleep_published_before_metrics_and_backups(self):
        with tempfile.TemporaryDirectory() as directory:
            events = []
            logs = sleep_records([session()]).logs
            class Google:
                def fetch_sleep(self, *args): return Complete(logs)
                def heart_rate(self, *args):
                    events.append('metrics')
                    return []
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory, now=lambda: 20000, google=Google())
            integration.state['active_provider'] = 'google_health'
            def publish(value):
                self.assertTrue(integration.path.exists())
                self.assertFalse((Path(directory) / 'fitbit_summary.json').exists())
                events.append('sleep')
            with patch('health_integration.energy_inputs', return_value={'is_real_today': True}):
                integration.get_all_energy_inputs(force=True, on_sleep=publish)
            self.assertEqual(events, ['sleep', 'metrics'])
            self.assertTrue((Path(directory) / 'fitbit_hr_intraday.json').exists())

    def test_sleep_only_poll_retains_last_metric_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            logs = sleep_records([session()]).logs
            integration = HealthIntegration(SimpleNamespace(tokens={}), directory, now=lambda: 20000,
                google=SimpleNamespace(fetch_sleep=lambda *args: Complete(logs)))
            points = [{'time': '03:00:00', 'value': 60}]
            integration.state.update(active_provider='google_health', heart_points=points, metrics_checked=19999)
            with patch('health_integration.energy_inputs', return_value={'is_real_today': True}):
                result = integration.get_all_energy_inputs()
            self.assertEqual(result['raw_hr_points'], points)

    def test_corrupt_active_state_never_falls_back_to_fitbit(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'health_state.json').write_text('broken')
            with self.assertRaisesRegex(Exception, 'CONFIGURATION'):
                HealthIntegration(SimpleNamespace(tokens={}), directory, google=object())


if __name__ == '__main__': unittest.main()
