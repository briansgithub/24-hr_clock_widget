"""Unit tests for empirical energy fit pipeline."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))

from analyze_energy_fit import (
    STATUS_INTERP,
    STATUS_LOGGED,
    STATUS_MISSED,
    STATUS_SLEEP,
    OUTLIER_ERROR_THRESHOLD,
    build_fitting_frame,
    build_fitbit_daily_table,
    compute_m4_residual_frame,
    interpolate_gaps,
    load_empirical_csv,
    make_daily_comparison_plots,
    predict_legacy_two_process,
)
from energy_models import ModelParams, predict_m4_circadian_baseline
from energy_logic import get_personal_energy, load_personal_fit, personal_m4_energy
from fit_awake_hybrid_model import (
    FitObservation,
    RawSlot,
    WakeEpisode,
    _group_folds,
    episode_weights,
    evaluate_promotion,
    interpolate_episode,
)
from export_energy_model import check_export, render_android_constants
from energy_models import (
    ModelParams,
    metrics,
    predict_m1_baseline,
    predict_m2_tuned,
    predict_m3_scaled_s,
    predict_m4_circadian_baseline,
)


class TestInterpolation(unittest.TestCase):
    def test_no_interpolation_across_sleep(self):
        rows = [
            {"Timestamp": 1, "DateTime": "2026-07-21 07:00:00", "EnergyLevel": 40, "Status": STATUS_LOGGED},
            {"Timestamp": 2, "DateTime": "2026-07-21 07:30:00", "EnergyLevel": None, "Status": STATUS_MISSED},
            {"Timestamp": 3, "DateTime": "2026-07-21 08:00:00", "EnergyLevel": None, "Status": STATUS_SLEEP},
            {"Timestamp": 4, "DateTime": "2026-07-21 08:30:00", "EnergyLevel": None, "Status": STATUS_MISSED},
            {"Timestamp": 5, "DateTime": "2026-07-21 09:00:00", "EnergyLevel": 60, "Status": STATUS_LOGGED},
        ]
        df = pd.DataFrame(rows)
        df["DateTime"] = pd.to_datetime(df["DateTime"])
        df["hour"] = df["DateTime"].dt.hour + df["DateTime"].dt.minute / 60.0
        df["date"] = df["DateTime"].dt.date.astype(str)
        out = interpolate_gaps(df)
        self.assertFalse((out["Status"] == STATUS_INTERP).any())

    def test_interpolation_between_logged(self):
        rows = [
            {"Timestamp": 1, "DateTime": "2026-07-21 14:00:00", "EnergyLevel": 40, "Status": STATUS_LOGGED},
            {"Timestamp": 2, "DateTime": "2026-07-21 14:30:00", "EnergyLevel": None, "Status": STATUS_MISSED},
            {"Timestamp": 3, "DateTime": "2026-07-21 15:00:00", "EnergyLevel": 60, "Status": STATUS_LOGGED},
        ]
        df = pd.DataFrame(rows)
        df["DateTime"] = pd.to_datetime(df["DateTime"])
        df["hour"] = df["DateTime"].dt.hour + df["DateTime"].dt.minute / 60.0
        df["date"] = df["DateTime"].dt.date.astype(str)
        out = interpolate_gaps(df)
        interp = out[out["Status"] == STATUS_INTERP]
        self.assertEqual(len(interp), 1)
        self.assertAlmostEqual(float(interp.iloc[0]["EnergyLevel"]), 50.0, places=1)


class TestAwakeHybridFitter(unittest.TestCase):
    @staticmethod
    def _episode(slots):
        wake = slots[0].datetime
        return WakeEpisode(
            episode_id="episode-a",
            wake=wake,
            end=slots[-1].datetime + timedelta(minutes=30),
            main_sleep_duration=8.0,
            debt_hours=0.0,
            nap_intervals=(),
            slots=slots,
            complete=True,
        )

    @staticmethod
    def _observation(episode_id, minute, energy=50.0):
        point = datetime(2026, 7, 21, 8, 0) + timedelta(minutes=minute)
        return FitObservation(
            episode_id=episode_id,
            datetime=point,
            clock_hour=point.hour + point.minute / 60.0,
            energy=energy,
            original=True,
            wake_hour=8.0,
            sleep_duration=8.0,
            debt_hours=0.0,
            nap_intervals=(),
            process_delta=0.0,
            hours_since_last_wake=minute / 60.0,
            debt_load=0.0,
        )

    def test_interpolation_never_crosses_sleep(self):
        start = datetime(2026, 7, 21, 7, 0)
        slots = [
            RawSlot(start, STATUS_LOGGED, 40.0),
            RawSlot(start + timedelta(minutes=30), STATUS_MISSED, None),
            RawSlot(start + timedelta(minutes=60), STATUS_SLEEP, None),
            RawSlot(start + timedelta(minutes=90), STATUS_MISSED, None),
            RawSlot(start + timedelta(minutes=120), STATUS_LOGGED, 60.0),
        ]
        rows = interpolate_episode(self._episode(slots), max_gap_slots=2)
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(row.original for row in rows))

    def test_only_one_or_two_slots_are_interpolated_and_not_scored(self):
        start = datetime(2026, 7, 21, 7, 0)
        slots = [
            RawSlot(start, STATUS_LOGGED, 40.0),
            RawSlot(start + timedelta(minutes=30), STATUS_MISSED, None),
            RawSlot(start + timedelta(minutes=60), STATUS_MISSED, None),
            RawSlot(start + timedelta(minutes=90), STATUS_LOGGED, 70.0),
        ]
        rows = interpolate_episode(self._episode(slots), max_gap_slots=2)
        self.assertEqual(len(rows), 4)
        self.assertEqual(sum(row.original for row in rows), 2)
        self.assertEqual([row.energy for row in rows], [40.0, 50.0, 60.0, 70.0])

    def test_episode_weights_have_equal_total_mass(self):
        rows = [
            self._observation("a", 0),
            self._observation("a", 30),
            self._observation("a", 60),
            self._observation("b", 0),
        ]
        weights = episode_weights(rows)
        mass_a = sum(weight for row, weight in zip(rows, weights) if row.episode_id == "a")
        mass_b = sum(weight for row, weight in zip(rows, weights) if row.episode_id == "b")
        self.assertAlmostEqual(mass_a, mass_b, places=12)

    def test_group_folds_never_split_an_episode(self):
        ids = ["a", "a", "b", "b", "c", "c", "d", "d"]
        folds = _group_folds(ids, 3)
        for episode_id in set(ids):
            self.assertEqual(sum(episode_id in fold for fold in folds), 1)

    def test_promotion_rejects_below_21_or_any_failed_gate(self):
        baseline = {"mae": 12.0, "rmse": 15.0}
        candidate = {"mae": 10.5, "rmse": 14.0}
        reference = {"passes": True}
        self.assertFalse(
            evaluate_promotion(20, baseline, candidate, 0.7, reference, 0.7)["passed"]
        )
        self.assertTrue(
            evaluate_promotion(21, baseline, candidate, 0.7, reference, 0.7)["passed"]
        )
        failures = [
            (21, {"mae": 12.0, "rmse": 15.0}, {"mae": 11.5, "rmse": 14.0}, 0.7, reference, 0.7),
            (21, baseline, {"mae": 10.5, "rmse": 15.1}, 0.7, reference, 0.7),
            (21, baseline, candidate, 0.59, reference, 0.7),
            (21, baseline, candidate, 0.7, {"passes": False}, 0.7),
            (21, baseline, candidate, 0.7, reference, 0.59),
        ]
        for args in failures:
            self.assertFalse(evaluate_promotion(*args)["passed"])

    def test_android_export_is_deterministic_and_detects_drift(self):
        model = {
            "model_id": "M8_adaptive_sleep",
            "params": {
                "circadian_coefs": [50.0, 1.0, 2.0, 3.0, 4.0],
                "reference_sleep_hours": 8.0,
                "reference_peak_clock": 20.0,
                "max_phase_shift_hours": 3.0,
                "tau_wake": 23.0,
                "tau_sleep": 4.0,
                "inertia_tau": 0.75,
                "homeostatic_scale": 20.0,
                "debt_scale": 2.0,
                "debt_saturation_hours": 12.0,
                "inertia_scale": 12.0,
                "process_s_min": 0.05,
                "process_s_max": 1.0,
                "homeostatic_delta_min": -0.15,
                "homeostatic_delta_max": 0.35,
            },
        }
        rendered = render_android_constants(model)
        self.assertEqual(rendered, render_android_constants(model))
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "model.json")
            android = os.path.join(tmp, "Generated.kt")
            with open(source, "w", encoding="utf-8") as handle:
                import json
                json.dump(model, handle)
            with open(android, "w", encoding="utf-8") as handle:
                handle.write(rendered + "// drift\n")
            with self.assertRaises(RuntimeError):
                check_export(Path(source), Path(android))


class TestWakeAlignment(unittest.TestCase):
    def test_hours_since_wake(self):
        rows = [
            {"Timestamp": 1, "DateTime": "2026-07-21 12:00:00", "EnergyLevel": 50, "Status": STATUS_LOGGED},
        ]
        df = pd.DataFrame(rows)
        df["DateTime"] = pd.to_datetime(df["DateTime"])
        df["hour"] = 12.0
        df["date"] = "2026-07-21"
        fitbit = pd.DataFrame([{"date": "2026-07-21", "wake_hour": 10.0, "sleep_duration": 7.0, "efficiency": 0.9}])
        frame = build_fitting_frame(df, fitbit, [], use_interpolation=False)
        self.assertEqual(len(frame), 1)
        self.assertAlmostEqual(frame.iloc[0]["t"], 2.0)


class TestModels(unittest.TestCase):
    def test_m1_at_bounds(self):
        e0 = predict_m1_baseline(0.0)
        e12 = predict_m1_baseline(12.0)
        self.assertGreaterEqual(e0, 0.0)
        self.assertLessEqual(e12, 150.0)

    def test_m4_returns_reasonable_range(self):
        p = ModelParams(
            baseline_intercept=50.0,
            circ_amp=15.0,
            circ_amp2=3.0,
            circ_offset=8.0,
        )
        e0 = predict_m4_circadian_baseline(0, 7.5, 2.0, 0.9, p)
        e12 = predict_m4_circadian_baseline(12, 7.5, 2.0, 0.9, p)
        self.assertGreaterEqual(e0, 0.0)
        self.assertLessEqual(e12, 100.0)

    def test_m2_m3_finite(self):
        p = ModelParams(tau_wake=15.0, tau_inertia=0.5, amp_primary=0.3)
        self.assertTrue(math.isfinite(predict_m2_tuned(6.0, 7.0, 1.0, params=p)))
        p.k_s = 0.5
        self.assertTrue(math.isfinite(predict_m3_scaled_s(6.0, 7.0, 1.0, params=p)))


class TestPersonalModel(unittest.TestCase):
    def test_personal_m4_loads(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "personal_energy_fit.json")
            with open(path, "w", encoding="utf-8") as f:
                f.write(
                    '{"model_id":"M4_circadian_daily_baseline","params":'
                    '{"baseline_intercept":50,"circ_amp":10,"circ_offset":8,"circ_amp2":2}}'
                )
            fit = load_personal_fit(path)
            self.assertIsNotNone(fit)
            e = personal_m4_energy(5.0, 7.5, 2.0, 0.9, fit["params"])
            self.assertGreaterEqual(e, 0.0)
            self.assertLessEqual(e, 1.0)

    def test_load_m4_params_merges_personal_k_s(self):
        from analyze_energy_fit import _load_m4_params
        from energy_models import ModelParams

        with tempfile.TemporaryDirectory() as tmp:
            personal = os.path.join(tmp, "personal.json")
            with open(personal, "w", encoding="utf-8") as f:
                f.write('{"params":{"k_s":0.2,"circ_amp":99}}')
            full_fit = {
                "M4_circadian_daily_baseline": ModelParams(k_s=1.0, circ_amp=10.0).to_dict()
            }
            p4 = _load_m4_params(pd.DataFrame(), personal_path=personal, full_fit=full_fit)
            self.assertAlmostEqual(p4.k_s, 0.2)
            self.assertAlmostEqual(p4.circ_amp, 10.0)


import math  # noqa: E402 — used in TestModels

import numpy as np  # noqa: E402
from datetime import datetime  # noqa: E402


class TestOutlierAnalysis(unittest.TestCase):
    def test_outlier_ranks_deliberate_spike_first(self):
        p4 = ModelParams(
            baseline_intercept=50.0,
            circ_amp=10.0,
            circ_amp2=2.0,
            circ_offset=8.0,
        )
        frame = pd.DataFrame([
            {
                "date": "2026-07-21",
                "hour": 14.0,
                "t": 4.0,
                "y": 55.0,
                "wake_hour": 10.0,
                "sleep_duration": 7.5,
                "sleep_debt_hours": 1.0,
                "sleep_efficiency": 0.9,
                "status": STATUS_LOGGED,
                "interpolated": False,
            },
            {
                "date": "2026-07-21",
                "hour": 14.5,
                "t": 4.5,
                "y": 95.0,
                "wake_hour": 10.0,
                "sleep_duration": 7.5,
                "sleep_debt_hours": 1.0,
                "sleep_efficiency": 0.9,
                "status": STATUS_LOGGED,
                "interpolated": False,
            },
            {
                "date": "2026-07-21",
                "hour": 15.0,
                "t": 5.0,
                "y": 56.0,
                "wake_hour": 10.0,
                "sleep_duration": 7.5,
                "sleep_debt_hours": 1.0,
                "sleep_efficiency": 0.9,
                "status": STATUS_LOGGED,
                "interpolated": False,
            },
        ])
        raw = pd.DataFrame([
            {
                "date": "2026-07-21",
                "hour": 14.5,
                "DateTime": pd.Timestamp("2026-07-21 14:30:00"),
                "Status": STATUS_LOGGED,
                "EnergyLevel": 95.0,
            },
            {
                "date": "2026-07-21",
                "hour": 14.0,
                "DateTime": pd.Timestamp("2026-07-21 14:00:00"),
                "Status": STATUS_LOGGED,
                "EnergyLevel": 55.0,
            },
            {
                "date": "2026-07-21",
                "hour": 15.0,
                "DateTime": pd.Timestamp("2026-07-21 15:00:00"),
                "Status": STATUS_LOGGED,
                "EnergyLevel": 56.0,
            },
        ])
        residuals = compute_m4_residual_frame(frame, raw, p4)
        self.assertEqual(len(residuals), 3)
        spike = residuals[residuals["y"] == 95.0].iloc[0]
        self.assertGreaterEqual(spike["abs_error"], OUTLIER_ERROR_THRESHOLD)
        self.assertEqual(spike["classification"], "data_suspect")
        self.assertNotEqual(spike["rationale"], "")

    def test_gap_spans_sleep_detected(self):
        from analyze_energy_fit import _gap_spans_sleep

        raw = pd.DataFrame([
            {
                "date": "2026-07-16",
                "DateTime": pd.Timestamp("2026-07-16 07:30:00"),
                "Status": STATUS_LOGGED,
            },
            {
                "date": "2026-07-16",
                "DateTime": pd.Timestamp("2026-07-16 08:00:00"),
                "Status": STATUS_SLEEP,
            },
            {
                "date": "2026-07-16",
                "DateTime": pd.Timestamp("2026-07-16 14:00:00"),
                "Status": STATUS_LOGGED,
            },
        ])
        self.assertTrue(
            _gap_spans_sleep(
                raw,
                pd.Timestamp("2026-07-16 07:30:00"),
                pd.Timestamp("2026-07-16 14:00:00"),
            )
        )

    def test_post_sleep_first_log_wake_variation(self):
        p4 = ModelParams()
        frame = pd.DataFrame([
            {
                "date": "2026-07-16",
                "hour": 14.0,
                "t": 0.4,
                "y": 28.0,
                "wake_hour": 13.6,
                "sleep_duration": 5.4,
                "sleep_debt_hours": 2.0,
                "sleep_efficiency": 0.93,
                "status": STATUS_LOGGED,
            },
        ])
        raw = pd.DataFrame([
            {
                "date": "2026-07-16",
                "hour": 7.5,
                "DateTime": pd.Timestamp("2026-07-16 07:30:00"),
                "Status": STATUS_LOGGED,
                "EnergyLevel": 5.0,
            },
            {
                "date": "2026-07-16",
                "hour": 8.0,
                "DateTime": pd.Timestamp("2026-07-16 08:00:00"),
                "Status": STATUS_SLEEP,
                "EnergyLevel": None,
            },
            {
                "date": "2026-07-16",
                "hour": 14.0,
                "DateTime": pd.Timestamp("2026-07-16 14:00:00"),
                "Status": STATUS_LOGGED,
                "EnergyLevel": 28.0,
            },
        ])
        residuals = compute_m4_residual_frame(frame, raw, p4, use_sleep_dynamics=False)
        row = residuals.iloc[0]
        self.assertTrue(row["gap_spans_sleep"])
        if row["abs_error"] >= OUTLIER_ERROR_THRESHOLD:
            self.assertEqual(row["classification"], "wake_variation")


    def test_perfect_fit_r2(self):
        import numpy as np
        y = np.array([50.0, 60.0, 70.0])
        m = metrics(y, y)
        self.assertAlmostEqual(m["mae"], 0.0)
        self.assertAlmostEqual(m["r2"], 1.0)


class TestDailyPlots(unittest.TestCase):
    def test_legacy_two_process_finite(self):
        val = predict_legacy_two_process(5.0, 7.5, 2.0)
        self.assertTrue(math.isfinite(val))
        self.assertGreaterEqual(val, 0.0)

    def test_make_daily_comparison_plots_writes_png(self):
        frame = pd.DataFrame([
            {
                "date": "2026-07-21",
                "hour": 14.0,
                "t": 4.0,
                "y": 55.0,
                "wake_hour": 10.0,
                "sleep_duration": 7.5,
                "sleep_debt_hours": 1.0,
                "sleep_efficiency": 0.9,
                "status": STATUS_LOGGED,
                "interpolated": False,
            },
            {
                "date": "2026-07-21",
                "hour": 16.0,
                "t": 6.0,
                "y": 60.0,
                "wake_hour": 10.0,
                "sleep_duration": 7.5,
                "sleep_debt_hours": 1.0,
                "sleep_efficiency": 0.9,
                "status": STATUS_LOGGED,
                "interpolated": False,
            },
        ])
        raw = pd.DataFrame([
            {
                "date": "2026-07-21",
                "hour": 8.0,
                "Status": STATUS_SLEEP,
            },
        ])
        with tempfile.TemporaryDirectory() as tmp:
            summaries = make_daily_comparison_plots(frame, raw, output_dir=tmp)
            self.assertEqual(len(summaries), 1)
            self.assertTrue(os.path.exists(os.path.join(tmp, "2026-07-21.png")))
            self.assertTrue(os.path.exists(os.path.join(tmp, "index.html")))


class TestSleepDynamics(unittest.TestCase):
    def test_s_discharge_during_sleep(self):
        from sleep_dynamics import s_discharge_while_asleep, s_build_while_awake

        s_wake = 0.8
        s_mid = s_discharge_while_asleep(s_wake, 2.0, tau_sleep=4.0)
        self.assertLess(s_mid, s_wake)
        s_end = s_discharge_while_asleep(s_wake, 8.0, tau_sleep=4.0)
        self.assertLess(s_end, s_mid)

    def test_nap_reduces_pressure_vs_no_nap(self):
        from sleep_dynamics import dynamics_at, build_sleep_episodes
        from energy_models import ModelParams

        logs = [
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": True,
                "startTime": "2026-07-21T04:00:00",
                "endTime": "2026-07-21T12:00:00",
                "minutesAsleep": 420,
                "timeInBed": 480,
            },
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": False,
                "startTime": "2026-07-21T15:00:00",
                "endTime": "2026-07-21T15:30:00",
                "minutesAsleep": 25,
                "timeInBed": 30,
            },
        ]
        p = ModelParams()
        no_nap = dynamics_at(datetime(2026, 7, 21, 18, 0), logs[:1], 8.0, p)
        with_nap = dynamics_at(datetime(2026, 7, 21, 18, 0), logs, 8.0, p)
        self.assertIsNotNone(no_nap)
        self.assertIsNotNone(with_nap)
        self.assertLess(with_nap.s_pressure, no_nap.s_pressure)

    def test_sleep_restore_proposal_for_sleep_slot(self):
        from data_review import audit_unlogged_gaps
        from energy_models import ModelParams
        import pandas as pd

        raw = pd.DataFrame([
            {
                "DateTime": datetime(2026, 7, 21, 8, 0),
                "hour": 8.0,
                "date": "2026-07-21",
                "Status": STATUS_SLEEP,
                "EnergyLevel": None,
            },
        ])
        logs = [{
            "dateOfSleep": "2026-07-21",
            "isMainSleep": True,
            "startTime": "2026-07-21T04:00:00",
            "endTime": "2026-07-21T12:00:00",
            "minutesAsleep": 420,
            "timeInBed": 480,
        }]
        props = audit_unlogged_gaps(raw, ModelParams(), [], logs, 8.0)
        self.assertEqual(props[0]["action"], "SLEEP_RESTORE")
        self.assertIsNone(props[0]["proposed_value"])
        self.assertIn("projected_wake_energy", props[0])


class TestDataReview(unittest.TestCase):
    def test_find_energy_extrema_detects_peak_and_trough(self):
        from energy_models import find_energy_extrema
        t = [0.0, 1.0, 2.0, 3.0, 4.0]
        y = [30.0, 50.0, 70.0, 50.0, 30.0]
        ext = find_energy_extrema(t, y, wake_hour=10.0, min_prominence=10.0)
        self.assertGreater(len(ext), 0)

    def test_wake_context_assigns_hours_since_wake(self):
        from empirical_sleep import build_wake_episodes, wake_context_for_datetime
        logs = [{
            "dateOfSleep": "2026-07-21",
            "isMainSleep": True,
            "startTime": "2026-07-21T04:00:00",
            "endTime": "2026-07-21T12:30:00",
            "minutesAsleep": 420,
            "timeInBed": 480,
        }]
        eps = build_wake_episodes(logs)
        ctx = wake_context_for_datetime(
            datetime(2026, 7, 22, 1, 0), eps, logs, 8.0
        )
        self.assertIsNotNone(ctx)
        self.assertGreater(ctx["t"], 10.0)


class TestEmpiricalSleep(unittest.TestCase):
    def test_infer_wake_uses_first_logged_after_main_sleep_block(self):
        from empirical_sleep import infer_wake_from_sleep_excluded, infer_sleep_duration_from_day

        rows = []
        for h in [0.0, 0.5, 1.0, 1.5, 2.0, 2.5]:
            rows.append({
                "DateTime": f"2026-07-20 {int(h):02d}:{int((h % 1) * 60):02d}:00",
                "hour": h,
                "Status": STATUS_LOGGED,
                "EnergyLevel": 80.0,
            })
        for h in [4.0, 4.5, 5.0, 5.5, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0]:
            rows.append({
                "DateTime": f"2026-07-20 {int(h):02d}:{int((h % 1) * 60):02d}:00",
                "hour": h,
                "Status": STATUS_SLEEP,
                "EnergyLevel": None,
            })
        rows.append({
            "DateTime": "2026-07-20 10:30:00",
            "hour": 10.5,
            "Status": STATUS_LOGGED,
            "EnergyLevel": 37.0,
        })
        day = pd.DataFrame(rows)
        self.assertAlmostEqual(infer_wake_from_sleep_excluded(day), 10.5)
        self.assertAlmostEqual(infer_sleep_duration_from_day(day), 6.5)

    def test_build_fitting_frame_uses_empirical_when_fitbit_missing(self):
        from empirical_sleep import build_empirical_daily_table

        csv_path = os.path.join(os.path.dirname(__file__), "..", "Alertness_Master_Log.csv")
        if not os.path.exists(csv_path):
            self.skipTest("Alertness_Master_Log.csv not present")
        raw = load_empirical_csv(csv_path)
        if raw.empty:
            self.skipTest("Alertness_Master_Log.csv not present")
        empirical = build_empirical_daily_table(raw)
        fitbit_daily = pd.DataFrame(columns=["date", "wake_hour", "sleep_duration", "efficiency"])
        frame = build_fitting_frame(raw, fitbit_daily, [], sleep_need_hours=8.0)
        jul20 = frame[frame["date"] == "2026-07-20"].iloc[0]
        self.assertEqual(jul20["sleep_source"], "empirical_log")
        self.assertAlmostEqual(float(jul20["wake_hour"]), 10.5)
        emp_row = empirical[empirical["date"] == "2026-07-20"].iloc[0]
        self.assertAlmostEqual(float(jul20["sleep_duration"]), float(emp_row["sleep_duration"]))

    def test_wake_episode_assigns_prior_night_sleep_before_calendar_wake(self):
        logs = [
            {
                "dateOfSleep": "2026-07-19",
                "isMainSleep": True,
                "startTime": "2026-07-19T01:00:00",
                "endTime": "2026-07-19T10:00:00",
                "minutesAsleep": 480,
                "timeInBed": 540,
            },
            {
                "dateOfSleep": "2026-07-20",
                "isMainSleep": True,
                "startTime": "2026-07-20T01:00:00",
                "endTime": "2026-07-20T10:00:00",
                "minutesAsleep": 420,
                "timeInBed": 540,
            },
        ]
        raw = pd.DataFrame([
            {
                "DateTime": datetime(2026, 7, 20, 2, 0, 0),
                "hour": 2.0,
                "date": "2026-07-20",
                "Status": STATUS_LOGGED,
                "EnergyLevel": 80.0,
                "Timestamp": "t1",
            },
        ])
        fitbit_daily = build_fitbit_daily_table(logs)
        cal = build_fitting_frame(raw, fitbit_daily, logs, grouping="calendar")
        wake = build_fitting_frame(raw, fitbit_daily, logs, grouping="wake_episode")
        self.assertEqual(cal.iloc[0]["wake_episode_date"], "2026-07-20")
        self.assertEqual(wake.iloc[0]["wake_episode_date"], "2026-07-19")
        self.assertAlmostEqual(wake.iloc[0]["sleep_duration"], 8.0, places=1)
        self.assertEqual(wake.iloc[0]["sleep_grouping"], "wake_episode")
        self.assertFalse(wake.iloc[0]["pre_wake_on_calendar_date"])


class TestSleepInclusion(unittest.TestCase):
    def test_sync_inclusion_auto_includes_past_dates(self):
        from energy_logic import sync_sleep_inclusion

        excluded = sync_sleep_inclusion(
            excluded_dates=["2026-07-16", "2026-07-17", "2026-07-18", "2026-07-19", "2026-07-22"],
            explicit_dates=[],
            raw_logs=[
                {"dateOfSleep": "2026-07-16", "isMainSleep": True},
                {"dateOfSleep": "2026-07-22", "isMainSleep": True},
            ],
            today_str="2026-07-22",
        )
        self.assertNotIn("2026-07-16", excluded)
        self.assertNotIn("2026-07-17", excluded)
        self.assertNotIn("2026-07-22", excluded)

    def test_sync_inclusion_excludes_today_without_main_sleep(self):
        from energy_logic import sync_sleep_inclusion

        excluded = sync_sleep_inclusion(
            excluded_dates=[],
            explicit_dates=[],
            raw_logs=[{"dateOfSleep": "2026-07-22", "isMainSleep": False}],
            today_str="2026-07-22",
        )
        self.assertIn("2026-07-22", excluded)

    def test_sync_inclusion_respects_explicit_past_exclusion(self):
        from energy_logic import sync_sleep_inclusion

        excluded = sync_sleep_inclusion(
            excluded_dates=["2026-07-16"],
            explicit_dates=["2026-07-16"],
            raw_logs=[{"dateOfSleep": "2026-07-16", "isMainSleep": True}],
            today_str="2026-07-22",
        )
        self.assertIn("2026-07-16", excluded)


    def test_sleep_table_sessions_lists_nap_rows(self):
        from energy_logic import sleep_table_sessions

        logs = [
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": True,
                "startTime": "2026-07-20T22:02:00",
                "minutesAsleep": 451,
            },
            {
                "dateOfSleep": "2026-07-21",
                "isMainSleep": False,
                "startTime": "2026-07-21T09:13:00",
                "minutesAsleep": 65,
            },
        ]
        rows = sleep_table_sessions(logs, "2026-07-21", include_naps=True)
        self.assertEqual([kind for kind, _ in rows], ["main", "nap"])
        rows_off = sleep_table_sessions(logs, "2026-07-21", include_naps=False)
        self.assertEqual([kind for kind, _ in rows_off], ["main"])


class TestEnergyDisplayPct(unittest.TestCase):
    """Energy % label must use today's curve peak, not the perfection reference."""

    def test_today_peak_pct_never_exceeds_100(self):
        from energy_logic import get_energy_level

        wake = 12.0
        bathy = 4.0
        goal = 9.7
        for debt in (0.0, 1.0, 2.0):
            for dur in (4.0, 6.0, 8.0):
                today_levels = [
                    get_energy_level((i / 1440.0) * 24.0, wake, debt, dur, bathy, clamp=False)
                    for i in range(1441)
                ]
                today_peak = max(today_levels)
                perfection_peak = max(
                    get_energy_level((i / 1440.0) * 24.0, wake, 0.0, goal, bathy, clamp=False)
                    for i in range(1441)
                )
                for cur in today_levels[::60]:
                    pct_today = min(100, max(0, int(round((cur / today_peak) * 100)))) if today_peak else 0
                    pct_wrong = int(round((cur / perfection_peak) * 100)) if perfection_peak else 0
                    self.assertLessEqual(pct_today, 100)
                self.assertGreaterEqual(today_peak, max(today_levels))


if __name__ == "__main__":
    unittest.main()
