"""Focused tests for the shared M8 adaptive sleep-energy contract."""

from __future__ import annotations

import datetime
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))

from energy_logic import (
    ADAPTIVE_MODEL_ID,
    ADAPTIVE_MODEL_DEFAULTS,
    AWAKE_HYBRID_MODEL_ID,
    ClockSleepInterval,
    EnergyCurve,
    EnergyPredictionState,
    SleepIntervalKind,
    adaptive_sleep_energy,
    awake_clock_segments,
    compute_sleep_debt,
    get_energy_level,
    load_personal_fit,
    predict_energy,
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

    def test_invalid_m9_configuration_falls_back_to_committed_m8(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "invalid_m9.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(
                    {"schema_version": 2, "model_id": AWAKE_HYBRID_MODEL_ID, "params": {}},
                    handle,
                )
            fit = load_personal_fit(path)
        self.assertEqual(fit["model_id"], ADAPTIVE_MODEL_ID)
        self.assertIn("fallback_reason", fit)

    def test_valid_m9_accepts_a_third_harmonic(self):
        params = dict(ADAPTIVE_MODEL_DEFAULTS)
        params["harmonics"] = 3
        params["circadian_coefs"] = [50.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0]
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "valid_m9.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(
                    {
                        "schema_version": 2,
                        "model_id": AWAKE_HYBRID_MODEL_ID,
                        "params": params,
                    },
                    handle,
                )
            fit = load_personal_fit(path)
        self.assertEqual(fit["model_id"], AWAKE_HYBRID_MODEL_ID)

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
        self.assertLess(with_nap_immediate, no_nap_immediate)
        self.assertGreater(with_nap_later, no_nap_later)

    def test_shared_reference_vectors(self):
        cases = [
            ((14.0, 9.0, 7.0, 3.0, None, None, ()), 0.6464512909036823),
            ((20.0, 9.0, 8.0, 0.0, 5.0, None, ()), 0.6801698896361507),
            ((18.0, 8.0, 5.5, 8.0, None, 12.0, ((4.0, 4.5),)), 0.6397337294750542),
            ((8.0, 8.0, 8.0, 0.0, None, None, ()), 0.28325070000118563),
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
        context = sleep_model_context(logs, "2026-07-21", include_naps=True)
        self.assertEqual(context.main_sleep_duration, 7.0)
        self.assertEqual(context.modeled_nap_intervals, ((5.0, 5.5),))
        self.assertEqual(len(context.all_sleep_intervals), 2)

        context_off = sleep_model_context(logs, "2026-07-21", include_naps=False)
        self.assertEqual(context_off.modeled_nap_intervals, ())
        self.assertEqual(len(context_off.all_sleep_intervals), 2)

    def test_sleep_state_boundaries_and_overnight_wraparound(self):
        logs = [
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": True,
                "startTime": "2026-07-20T23:00:00",
                "endTime": "2026-07-21T07:00:00",
                "minutesAsleep": 450,
            },
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": False,
                "startTime": "2026-07-21T14:00:00",
                "endTime": "2026-07-21T14:30:00",
                "minutesAsleep": 25,
            },
        ]
        context = sleep_model_context(logs, "2026-07-21", include_naps=True)
        common = dict(wake_hour=7.0, sleep_context=context, sleep_duration=7.5)
        self.assertEqual(predict_energy(23.0, **common).state, EnergyPredictionState.ASLEEP)
        self.assertEqual(predict_energy(1.0, **common).state, EnergyPredictionState.ASLEEP)
        wake = predict_energy(7.0, **common)
        self.assertEqual(wake.state, EnergyPredictionState.AWAKE)
        self.assertIsNotNone(wake.value)
        self.assertEqual(predict_energy(14.0, **common).state, EnergyPredictionState.ASLEEP)
        self.assertEqual(
            predict_energy(14.5, **common).state,
            EnergyPredictionState.AWAKE,
        )

    def test_disabling_nap_effects_keeps_nap_mask(self):
        logs = [
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": True,
                "startTime": "2026-07-21T00:00:00",
                "endTime": "2026-07-21T08:00:00",
                "minutesAsleep": 450,
            },
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": False,
                "startTime": "2026-07-21T12:00:00",
                "endTime": "2026-07-21T12:30:00",
                "minutesAsleep": 25,
            },
        ]
        with_effects = sleep_model_context(logs, "2026-07-21", include_naps=True)
        without_effects = sleep_model_context(logs, "2026-07-21", include_naps=False)
        common = dict(wake_hour=8.0, sleep_duration=7.5)
        self.assertEqual(
            predict_energy(12.25, sleep_context=with_effects, **common).state,
            EnergyPredictionState.ASLEEP,
        )
        self.assertEqual(
            predict_energy(12.25, sleep_context=without_effects, **common).state,
            EnergyPredictionState.ASLEEP,
        )
        self.assertLess(
            predict_energy(12.5, sleep_context=with_effects, **common).value,
            predict_energy(12.5, sleep_context=without_effects, **common).value,
        )

    def test_missing_main_sleep_is_unavailable(self):
        context = sleep_model_context([], "2026-07-21", include_naps=True)
        prediction = predict_energy(12.0, 8.0, context)
        self.assertEqual(prediction.state, EnergyPredictionState.UNAVAILABLE)
        self.assertIsNone(prediction.value)

    def test_awake_segments_do_not_cross_even_short_sleep(self):
        intervals = (
            ClockSleepInterval(23.0, 7.0, SleepIntervalKind.MAIN),
            ClockSleepInterval(14.1, 14.15, SleepIntervalKind.NAP),
        )
        segments = awake_clock_segments(intervals)
        self.assertEqual(segments, ((7.0, 14.1), (14.15, 23.0)))
        for start, end in segments:
            midpoint = ((start + end) / 2.0) % 24.0
            self.assertFalse(any(interval.contains(midpoint) for interval in intervals))

    def test_curve_range_uses_awake_values_only(self):
        context = sleep_model_context(
            [{
                "dateOfSleep": "2026-07-21",
                "isMainSleep": True,
                "startTime": "2026-07-20T23:00:00",
                "endTime": "2026-07-21T07:00:00",
                "minutesAsleep": 450,
            }],
            "2026-07-21",
        )
        curve = EnergyCurve(None)
        curve.wake_hour = 7.0
        curve.sleep_context = context
        curve.get_cached_energy(12.0, steps=144)
        awake = [value for value in curve._cached_levels if value is not None]
        self.assertGreater(len(awake), 0)
        self.assertGreater(curve._cached_levels.count(None), 0)
        self.assertEqual(curve._cached_e_min, min(awake))
        self.assertEqual(curve._cached_e_max, max(awake))


if __name__ == "__main__":
    unittest.main()
