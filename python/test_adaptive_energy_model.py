"""Focused tests for the shared M8 adaptive sleep-energy contract."""

from __future__ import annotations

import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))

from energy_logic import (
    ADAPTIVE_MODEL_ID,
    adaptive_sleep_energy,
    compute_sleep_debt,
    get_energy_level,
    load_personal_fit,
    sleep_model_context,
)


class TestAdaptiveSleepEnergy(unittest.TestCase):
    def test_config_activates_m8(self):
        fit = load_personal_fit()
        self.assertIsNotNone(fit)
        self.assertEqual(fit["model_id"], ADAPTIVE_MODEL_ID)
        self.assertAlmostEqual(
            get_energy_level(14.0, 9.0, 3.0, 7.0, use_personal_model=True),
            adaptive_sleep_energy(14.0, 9.0, 7.0, 3.0, params=fit["params"]),
            places=12,
        )

    def test_energy_is_bounded_across_day(self):
        for half_hour in range(48):
            energy = adaptive_sleep_energy(
                clock_hour=half_hour / 2.0,
                wake_hour=8.0,
                sleep_duration=6.5,
                sleep_debt_hours=8.0,
                nap_intervals=((5.0, 5.5),),
            )
            self.assertGreaterEqual(energy, 0.0)
            self.assertLessEqual(energy, 1.0)

    def test_more_debt_and_shorter_sleep_do_not_raise_energy(self):
        common = dict(clock_hour=18.0, wake_hour=8.0)
        rested = adaptive_sleep_energy(sleep_duration=8.0, sleep_debt_hours=0.0, **common)
        short_sleep = adaptive_sleep_energy(sleep_duration=5.0, sleep_debt_hours=0.0, **common)
        high_debt = adaptive_sleep_energy(sleep_duration=8.0, sleep_debt_hours=20.0, **common)
        self.assertLess(short_sleep, rested)
        self.assertLess(high_debt, rested)

    def test_nap_recovery_and_inertia_are_time_local(self):
        common = dict(wake_hour=8.0, sleep_duration=5.5, sleep_debt_hours=0.0)
        no_nap_immediate = adaptive_sleep_energy(clock_hour=12.5, nap_intervals=(), **common)
        with_nap_immediate = adaptive_sleep_energy(
            clock_hour=12.5, nap_intervals=((4.0, 4.5),), **common
        )
        no_nap_later = adaptive_sleep_energy(clock_hour=18.0, nap_intervals=(), **common)
        with_nap_later = adaptive_sleep_energy(
            clock_hour=18.0, nap_intervals=((4.0, 4.5),), **common
        )
        asleep = adaptive_sleep_energy(
            clock_hour=12.25, nap_intervals=((4.0, 4.5),), **common
        )
        self.assertLess(with_nap_immediate, no_nap_immediate)
        self.assertGreater(with_nap_later, no_nap_later)
        self.assertEqual(asleep, 0.0)

    def test_shared_reference_vectors(self):
        cases = [
            ((14.0, 9.0, 7.0, 3.0, None, None, ()), 0.6599810557700918),
            ((20.0, 9.0, 8.0, 0.0, 5.0, None, ()), 0.6594005145987748),
            ((18.0, 8.0, 5.5, 8.0, None, 12.0, ((4.0, 4.5),)), 0.6424203612753479),
            ((8.0, 8.0, 8.0, 0.0, None, None, ()), 0.2978989746884647),
        ]
        for args, expected in cases:
            self.assertAlmostEqual(adaptive_sleep_energy(*args), expected, places=12)


class TestAdaptiveSleepInputs(unittest.TestCase):
    def test_missing_dates_do_not_create_zero_sleep_deficits(self):
        yesterday = (datetime.datetime.now() - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
        logs = [{"dateOfSleep": yesterday, "minutesAsleep": 420, "isMainSleep": True}]
        self.assertAlmostEqual(compute_sleep_debt(logs, 8.0), 1.0, places=12)

    def test_observed_oversleep_repays_prior_debt(self):
        now = datetime.datetime.now()
        two_days_ago = (now - datetime.timedelta(days=2)).strftime("%Y-%m-%d")
        yesterday = (now - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
        logs = [
            {"dateOfSleep": two_days_ago, "minutesAsleep": 360, "isMainSleep": True},
            {"dateOfSleep": yesterday, "minutesAsleep": 540, "isMainSleep": True},
        ]
        self.assertAlmostEqual(compute_sleep_debt(logs, 8.0), 0.8, places=12)

    def test_main_sleep_and_naps_are_kept_separate(self):
        logs = [
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": True,
                "startTime": "2026-07-21T01:00:00",
                "endTime": "2026-07-21T09:00:00",
                "minutesAsleep": 420,
            },
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": False,
                "startTime": "2026-07-21T14:00:00",
                "endTime": "2026-07-21T14:30:00",
                "minutesAsleep": 25,
            },
        ]
        duration, naps = sleep_model_context(logs, "2026-07-21", include_naps=True)
        self.assertEqual(duration, 7.0)
        self.assertEqual(naps, ((5.0, 5.5),))
        _, naps_off = sleep_model_context(logs, "2026-07-21", include_naps=False)
        self.assertEqual(naps_off, ())


if __name__ == "__main__":
    unittest.main()
