import unittest
from copy import deepcopy
from health_contract import Complete, Pending, SleepStatus, sleep_records, reconcile, minute_heart_rate


def session(record='main', start='2026-09-15T02:00:00Z', end='2026-09-15T10:00:00Z', minutes=420, **metadata):
    return {'dataPointName': f'users/test/dataTypes/sleep/dataPoints/{record}', 'sleep': {
        'interval': {'startTime': start, 'endTime': end, 'startUtcOffset': '-14400s', 'endUtcOffset': '-14400s'},
        'type': 'STAGES', 'metadata': {'processed': True, **metadata}, 'summary': {'minutesAsleep': str(minutes)}}}


class HealthContractTests(unittest.TestCase):
    def test_shared_clock_fixtures(self):
        import json
        from pathlib import Path
        fixtures = json.loads((Path(__file__).parents[1] / 'fixtures/google-health-sleep.json').read_text())
        for case in fixtures:
            with self.subTest(case=case['name']):
                result = sleep_records(case['points'])
                self.assertIsInstance(result, Complete)
                self.assertEqual([s['dateOfSleep'] for s in result.logs], case['dates'])
                self.assertEqual([s['timeInBed'] for s in result.logs], case['timeInBed'])
                self.assertEqual([s['isMainSleep'] for s in result.logs], case['main'])

    def test_offsets_main_and_successive_naps(self):
        points = [session(mainSleep=True),
                  session('nap1', '2026-09-15T16:00:00Z', '2026-09-15T16:30:00Z', 25, mainSleep=False, nap=True),
                  session('nap2', '2026-09-15T20:00:00Z', '2026-09-15T20:30:00Z', 25, mainSleep=False, nap=True)]
        result = sleep_records(points)
        self.assertIsInstance(result, Complete)
        self.assertEqual(len(result.logs), 3)
        self.assertEqual(result.logs[0]['startTime'], '2026-09-14T22:00:00.000')
        self.assertEqual(result.logs[0]['dateOfSleep'], '2026-09-15')
        self.assertEqual(result.logs[0]['timeInBed'], 480)
        self.assertEqual([s['isMainSleep'] for s in result.logs], [True, False, False])

    def test_pending_missing_summary_does_not_delete(self):
        complete = sleep_records([session()]).logs
        point = session(processed=False)
        point['sleep'].pop('summary')
        pending = sleep_records([point])
        self.assertIsInstance(pending, Pending)
        self.assertEqual(reconcile(complete, pending.logs, '2026-09-14', '2026-09-15', False), complete)
        self.assertEqual(reconcile(complete, [], '2026-09-14', '2026-09-15', True), [])

    def test_correction_and_provider_identity(self):
        old = sleep_records([session()]).logs
        new = sleep_records([session(minutes=410)]).logs
        self.assertEqual(reconcile(old, new, '2026-09-14', '2026-09-15', False), new)
        legacy = dict(old[0], provider='fitbit')
        self.assertEqual(len(reconcile([legacy], new, '2026-09-14', '2026-09-15', False)), 2)

    def test_main_schema_precedence_and_fallback(self):
        self.assertFalse(sleep_records([session(mainSleep=False, main=True)]).logs[0]['isMainSleep'])
        self.assertTrue(sleep_records([session(main=True)]).logs[0]['isMainSleep'])
        self.assertTrue(sleep_records([session()]).logs[0]['isMainSleep'])

    def test_dst_duration_uses_instants(self):
        point = session(start='2026-11-01T04:00:00Z', end='2026-11-01T12:00:00Z')
        point['sleep']['interval']['endUtcOffset'] = '-18000s'
        log = sleep_records([point]).logs[0]
        self.assertEqual(log['timeInBed'], 480)
        self.assertTrue(log['endTime'].endswith('07:00:00.000'))

    def test_bad_record_fails_range(self):
        bad = session()
        bad['sleep']['interval'].pop('endUtcOffset')
        with self.assertRaises((KeyError, ValueError)):
            sleep_records([session(), bad])

    def test_freshness_boundaries_and_unchanged_verification(self):
        status = SleepStatus(last_verified=100)
        self.assertEqual(status.opacity(3699), 1)
        self.assertEqual(status.opacity(3700), .6)
        self.assertEqual(status.opacity(14500), .3)
        status.last_verified = 14500
        self.assertEqual(status.opacity(14500), 1)
        status.error = 'AUTH_REQUIRED'
        self.assertEqual(status.opacity(14500), .3)
        self.assertEqual(SleepStatus().opacity(1), .3)

    def test_hr_duplicates_and_minute_averaging(self):
        def hr(second, value):
            return {'heartRate': {'sampleTime': {'physicalTime': f'2026-09-15T12:00:{second:02d}Z', 'utcOffset': '-14400s'}, 'beatsPerMinute': str(value)}}
        points = minute_heart_rate([hr(0, 60), hr(0, 60), hr(30, 70)])
        self.assertEqual(len(points), 1)
        self.assertEqual(points[0]['value'], 65)
        self.assertEqual(points[0]['time'], '08:00:00')


if __name__ == '__main__':
    unittest.main()
