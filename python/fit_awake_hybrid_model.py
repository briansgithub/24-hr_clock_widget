"""Fit and validate the provisional M9 awake-only hybrid energy model.

This is the canonical, reproducible fitter for ``Alertness_Master_Log.csv``.
It never changes production configuration: candidate parameters and validation
artifacts are written below ``analysis/``. Promotion is a separate explicit
export step after every gate in this report passes.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np

from energy_logic import ADAPTIVE_MODEL_DEFAULTS, adaptive_sleep_energy


MODEL_ID = "M9_awake_hybrid"
SCHEMA_VERSION = 2
STATUS_LOGGED = "LOGGED"
STATUS_MISSED = "MISSED"
STATUS_SLEEP = "SLEEP_EXCLUDED"

REFERENCE_SLEEP_HOURS = 8.0
TAU_WAKE = 23.0
TAU_SLEEP = 4.0
DEBT_SATURATION_HOURS = 12.0
MAX_PHASE_SHIFT_HOURS = 3.0
PROCESS_S_MIN = 0.05
PROCESS_S_MAX = 1.0
HOMEOSTATIC_DELTA_MIN = -0.15
HOMEOSTATIC_DELTA_MAX = 0.35

INERTIA_SCALES = (0.0, 4.0, 8.0, 12.0, 16.0, 20.0)
INERTIA_TAUS = (0.5, 0.75, 1.0, 1.5, 2.0)
HOMEOSTATIC_SCALES = (0.0, 10.0, 20.0, 30.0, 40.0)
DEBT_SCALES = (0.0, 2.0, 5.0, 10.0)
RIDGE_STRENGTHS = (0.1, 1.0, 10.0, 100.0, 1000.0)


@dataclass(frozen=True)
class RawSlot:
    datetime: datetime
    status: str
    energy: float | None


@dataclass(frozen=True)
class SleepBlock:
    start: datetime
    end: datetime

    @property
    def duration_hours(self) -> float:
        return (self.end - self.start).total_seconds() / 3600.0


@dataclass
class WakeEpisode:
    episode_id: str
    wake: datetime
    end: datetime
    main_sleep_duration: float
    debt_hours: float
    nap_intervals: tuple[tuple[float, float], ...]
    slots: list[RawSlot]
    complete: bool

    @property
    def wake_hour(self) -> float:
        return self.wake.hour + self.wake.minute / 60.0 + self.wake.second / 3600.0

    @property
    def logged_count(self) -> int:
        return sum(
            slot.status == STATUS_LOGGED and slot.energy is not None
            for slot in self.slots
        )


@dataclass(frozen=True)
class FitObservation:
    episode_id: str
    datetime: datetime
    clock_hour: float
    energy: float
    original: bool
    wake_hour: float
    sleep_duration: float
    debt_hours: float
    nap_intervals: tuple[tuple[float, float], ...]
    process_delta: float
    hours_since_last_wake: float
    debt_load: float


@dataclass(frozen=True, order=True)
class CorrectionParams:
    inertia_scale: float
    inertia_tau: float
    homeostatic_scale: float
    debt_scale: float


@dataclass
class HybridFit:
    harmonics: int
    coefs: np.ndarray
    correction: CorrectionParams
    ridge_strength: float
    episode_intercepts: dict[str, float]


def read_slots(csv_path: Path) -> list[RawSlot]:
    slots: list[RawSlot] = []
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        for raw in csv.DictReader(handle):
            energy_text = (raw.get("EnergyLevel") or "").strip()
            slots.append(
                RawSlot(
                    datetime=datetime.strptime(raw["DateTime"], "%Y-%m-%d %H:%M:%S"),
                    status=(raw.get("Status") or "").strip(),
                    energy=float(energy_text) if energy_text else None,
                )
            )
    return sorted(slots, key=lambda slot: slot.datetime)


def _infer_slot_hours(slots: Sequence[RawSlot]) -> float:
    differences = sorted(
        (right.datetime - left.datetime).total_seconds() / 3600.0
        for left, right in zip(slots[:-1], slots[1:])
        if 0.0 < (right.datetime - left.datetime).total_seconds() <= 7200.0
    )
    return differences[len(differences) // 2] if differences else 0.5


def _sleep_blocks(slots: Sequence[RawSlot], slot_hours: float) -> list[SleepBlock]:
    blocks: list[SleepBlock] = []
    start: datetime | None = None
    last: datetime | None = None
    tolerance = timedelta(hours=slot_hours + 0.02)
    for slot in slots:
        if slot.status == STATUS_SLEEP:
            if last is not None and slot.datetime - last > tolerance:
                blocks.append(SleepBlock(start, last + timedelta(hours=slot_hours)))
                start = None
            if start is None:
                start = slot.datetime
            last = slot.datetime
        elif start is not None and last is not None:
            blocks.append(SleepBlock(start, last + timedelta(hours=slot_hours)))
            start = last = None
    if start is not None and last is not None:
        blocks.append(SleepBlock(start, last + timedelta(hours=slot_hours)))
    return blocks


def build_wake_episodes(
    slots: Sequence[RawSlot],
    minimum_main_sleep_hours: float = 3.0,
) -> list[WakeEpisode]:
    """Build wake-to-main-sleep episodes; short sleep blocks remain naps."""
    if not slots:
        return []
    slot_hours = _infer_slot_hours(slots)
    blocks = _sleep_blocks(slots, slot_hours)
    main_blocks = [
        block for block in blocks
        if block.duration_hours >= minimum_main_sleep_hours
    ]
    episodes: list[WakeEpisode] = []
    debt = 0.0
    data_end = slots[-1].datetime + timedelta(hours=slot_hours)

    for index, main_sleep in enumerate(main_blocks):
        wake = main_sleep.end
        next_sleep = main_blocks[index + 1] if index + 1 < len(main_blocks) else None
        end = next_sleep.start if next_sleep is not None else data_end
        if end <= wake:
            continue
        debt = max(
            0.0,
            debt * 0.9 + REFERENCE_SLEEP_HOURS - main_sleep.duration_hours,
        )
        episode_slots = [slot for slot in slots if wake <= slot.datetime < end]
        naps = tuple(
            (
                (block.start - wake).total_seconds() / 3600.0,
                (block.end - wake).total_seconds() / 3600.0,
            )
            for block in blocks
            if block.duration_hours < minimum_main_sleep_hours
            and wake <= block.start < end
        )
        episodes.append(
            WakeEpisode(
                episode_id=wake.isoformat(timespec="minutes"),
                wake=wake,
                end=end,
                main_sleep_duration=main_sleep.duration_hours,
                debt_hours=debt,
                nap_intervals=naps,
                slots=episode_slots,
                complete=next_sleep is not None,
            )
        )
    return episodes


def _process_s_after_sleep(duration: float) -> float:
    duration = min(24.0, max(0.0, duration))
    awake_duration = 24.0 - duration
    e_wake = math.exp(-awake_duration / TAU_WAKE)
    e_sleep = math.exp(-duration / TAU_SLEEP)
    divisor = max(0.0001, 1.0 - e_wake * e_sleep)
    return min(
        PROCESS_S_MAX,
        max(
            PROCESS_S_MIN,
            (
                PROCESS_S_MAX * e_sleep * (1.0 - e_wake)
                + PROCESS_S_MIN * (1.0 - e_sleep)
            ) / divisor,
        ),
    )


def _process_s_awake(state: float, hours: float) -> float:
    return state + (PROCESS_S_MAX - state) * (
        1.0 - math.exp(-max(0.0, hours) / TAU_WAKE)
    )


def _process_s_asleep(state: float, hours: float) -> float:
    return PROCESS_S_MIN + (state - PROCESS_S_MIN) * math.exp(
        -max(0.0, hours) / TAU_SLEEP
    )


def _process_features(
    hours_since_main_wake: float,
    sleep_duration: float,
    nap_intervals: Sequence[tuple[float, float]],
) -> tuple[float, float]:
    actual = _process_s_after_sleep(sleep_duration)
    rested = _process_s_after_sleep(REFERENCE_SLEEP_HOURS)
    target = min(24.0, max(0.0, hours_since_main_wake))
    cursor = 0.0
    last_wake = 0.0
    for start, end in sorted(nap_intervals):
        start = min(24.0, max(0.0, start))
        end = min(24.0, max(0.0, end))
        if end <= start or start >= target:
            continue
        awake_hours = max(0.0, start - cursor)
        actual = _process_s_awake(actual, awake_hours)
        rested = _process_s_awake(rested, awake_hours)
        sleep_end = min(target, end)
        actual = _process_s_asleep(actual, sleep_end - start)
        rested = _process_s_asleep(rested, sleep_end - start)
        if target < end:
            return actual - rested, 0.0
        cursor = end
        last_wake = end
    awake_hours = max(0.0, target - cursor)
    actual = _process_s_awake(actual, awake_hours)
    rested = _process_s_awake(rested, awake_hours)
    return actual - rested, max(0.0, target - last_wake)


def interpolate_episode(
    episode: WakeEpisode,
    max_gap_slots: int = 2,
) -> list[FitObservation]:
    """Interpolate one/two MISSED slots inside an episode; score originals only."""
    slots = sorted(episode.slots, key=lambda slot: slot.datetime)
    values: list[float | None] = [
        slot.energy if slot.status == STATUS_LOGGED and slot.energy is not None else None
        for slot in slots
    ]
    original = [value is not None for value in values]
    logged_indices = [index for index, value in enumerate(values) if value is not None]
    slot_hours = _infer_slot_hours(slots)
    tolerance_seconds = 2.0

    for left, right in zip(logged_indices[:-1], logged_indices[1:]):
        gap = slots[left + 1:right]
        if not 1 <= len(gap) <= max_gap_slots:
            continue
        if any(slot.status != STATUS_MISSED for slot in gap):
            continue
        sequence = slots[left:right + 1]
        if any(
            abs(
                (b.datetime - a.datetime).total_seconds()
                - slot_hours * 3600.0
            ) > tolerance_seconds
            for a, b in zip(sequence[:-1], sequence[1:])
        ):
            continue
        left_value = float(values[left])
        right_value = float(values[right])
        for index in range(left + 1, right):
            fraction = (index - left) / (right - left)
            values[index] = left_value + fraction * (right_value - left_value)

    observations: list[FitObservation] = []
    debt_load = 1.0 - math.exp(
        -max(0.0, episode.debt_hours) / DEBT_SATURATION_HOURS
    )
    for index, value in enumerate(values):
        if value is None:
            continue
        slot = slots[index]
        elapsed = (slot.datetime - episode.wake).total_seconds() / 3600.0
        process_delta, hours_since_last_wake = _process_features(
            elapsed,
            episode.main_sleep_duration,
            episode.nap_intervals,
        )
        observations.append(
            FitObservation(
                episode_id=episode.episode_id,
                datetime=slot.datetime,
                clock_hour=(
                    slot.datetime.hour
                    + slot.datetime.minute / 60.0
                    + slot.datetime.second / 3600.0
                ),
                energy=float(value),
                original=original[index],
                wake_hour=episode.wake_hour,
                sleep_duration=episode.main_sleep_duration,
                debt_hours=episode.debt_hours,
                nap_intervals=episode.nap_intervals,
                process_delta=min(
                    HOMEOSTATIC_DELTA_MAX,
                    max(HOMEOSTATIC_DELTA_MIN, process_delta),
                ),
                hours_since_last_wake=hours_since_last_wake,
                debt_load=debt_load,
            )
        )
    return observations


def prepare_dataset(
    slots: Sequence[RawSlot],
    min_logged: int = 20,
    max_gap_slots: int = 2,
) -> tuple[list[WakeEpisode], list[FitObservation], dict]:
    episodes = build_wake_episodes(slots)
    qualified = [episode for episode in episodes if episode.logged_count >= min_logged]
    observations: list[FitObservation] = []
    interpolation_counts: dict[str, int] = {}
    for episode in qualified:
        episode_rows = interpolate_episode(episode, max_gap_slots)
        observations.extend(episode_rows)
        interpolation_counts[episode.episode_id] = sum(
            not row.original for row in episode_rows
        )
    all_logged = sum(
        slot.status == STATUS_LOGGED and slot.energy is not None for slot in slots
    )
    contextual_logged = sum(episode.logged_count for episode in episodes)
    qualified_logged = sum(row.original for row in observations)
    return qualified, observations, {
        "n_all_logged": all_logged,
        "n_contextual_logged": contextual_logged,
        "n_qualified_logged": qualified_logged,
        "contextual_coverage": contextual_logged / all_logged if all_logged else 0.0,
        "qualified_common_sample_coverage": qualified_logged / all_logged if all_logged else 0.0,
        "interpolated_counts": interpolation_counts,
    }


def fourier_design(hours: Iterable[float], harmonics: int) -> np.ndarray:
    hour_array = np.asarray(list(hours), dtype=float)
    theta = 2.0 * math.pi * hour_array / 24.0
    columns = [np.ones(len(theta))]
    for harmonic in range(1, harmonics + 1):
        columns.extend((np.cos(harmonic * theta), np.sin(harmonic * theta)))
    return np.column_stack(columns)


def episode_weights(rows: Sequence[FitObservation]) -> np.ndarray:
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.episode_id] = counts.get(row.episode_id, 0) + 1
    weights = np.asarray([1.0 / counts[row.episode_id] for row in rows], dtype=float)
    if weights.size:
        weights *= len(counts) / weights.sum()
    return weights


def _correction(rows: Sequence[FitObservation], params: CorrectionParams) -> np.ndarray:
    return np.asarray(
        [
            params.homeostatic_scale * row.process_delta
            + params.debt_scale * row.debt_load
            + params.inertia_scale
            * math.exp(-row.hours_since_last_wake / params.inertia_tau)
            for row in rows
        ],
        dtype=float,
    )


def fit_hybrid(
    rows: Sequence[FitObservation],
    harmonics: int,
    correction: CorrectionParams,
    ridge_strength: float = 0.0,
) -> HybridFit:
    if not rows:
        raise ValueError("Cannot fit an empty observation set")
    episode_ids = sorted({row.episode_id for row in rows})
    global_design = fourier_design((row.clock_hour for row in rows), harmonics)
    nuisance_count = max(0, len(episode_ids) - 1)
    nuisance = np.zeros((len(rows), nuisance_count), dtype=float)
    if nuisance_count:
        episode_index = {episode_id: index for index, episode_id in enumerate(episode_ids)}
        last_index = len(episode_ids) - 1
        for row_index, row in enumerate(rows):
            index = episode_index[row.episode_id]
            if index == last_index:
                nuisance[row_index, :] = -1.0
            else:
                nuisance[row_index, index] = 1.0
    design = np.column_stack((global_design, nuisance))
    target = np.asarray([row.energy for row in rows], dtype=float) + _correction(rows, correction)
    weights = episode_weights(rows)
    root_weights = np.sqrt(weights)
    weighted_design = design * root_weights[:, None]
    weighted_target = target * root_weights

    if harmonics >= 3 and ridge_strength > 0.0:
        penalty = np.zeros((2, design.shape[1]), dtype=float)
        penalty[0, 5] = math.sqrt(ridge_strength)
        penalty[1, 6] = math.sqrt(ridge_strength)
        weighted_design = np.vstack((weighted_design, penalty))
        weighted_target = np.concatenate((weighted_target, np.zeros(2)))

    beta, *_ = np.linalg.lstsq(weighted_design, weighted_target, rcond=None)
    global_count = 1 + 2 * harmonics
    offsets: dict[str, float] = {}
    if nuisance_count:
        for index, episode_id in enumerate(episode_ids[:-1]):
            offsets[episode_id] = float(beta[global_count + index])
        offsets[episode_ids[-1]] = -sum(offsets.values())
    else:
        offsets[episode_ids[0]] = 0.0
    return HybridFit(
        harmonics=harmonics,
        coefs=beta[:global_count],
        correction=correction,
        ridge_strength=ridge_strength,
        episode_intercepts=offsets,
    )


def predict_hybrid(
    fit: HybridFit,
    rows: Sequence[FitObservation],
    include_known_intercepts: bool = False,
) -> np.ndarray:
    if not rows:
        return np.asarray([], dtype=float)
    prediction = (
        fourier_design((row.clock_hour for row in rows), fit.harmonics) @ fit.coefs
        - _correction(rows, fit.correction)
    )
    if include_known_intercepts:
        prediction += np.asarray(
            [fit.episode_intercepts.get(row.episode_id, 0.0) for row in rows]
        )
    return np.clip(prediction, 0.0, 100.0)


def predict_m8(rows: Sequence[FitObservation]) -> np.ndarray:
    return np.asarray(
        [
            100.0 * adaptive_sleep_energy(
                clock_hour=row.clock_hour,
                wake_hour=row.wake_hour,
                sleep_duration=row.sleep_duration,
                sleep_debt_hours=row.debt_hours,
                nap_intervals=row.nap_intervals,
                params=ADAPTIVE_MODEL_DEFAULTS,
                clamp=True,
            )
            for row in rows
        ],
        dtype=float,
    )


def metric_summary(actual: np.ndarray, predicted: np.ndarray) -> dict[str, float | int]:
    error = predicted - actual
    return {
        "n": int(len(error)),
        "mae": float(np.mean(np.abs(error))) if len(error) else math.nan,
        "rmse": float(np.sqrt(np.mean(np.square(error)))) if len(error) else math.nan,
        "bias": float(np.mean(error)) if len(error) else math.nan,
    }


def _group_folds(episode_ids: Sequence[str], n_folds: int) -> list[set[str]]:
    ordered = sorted(set(episode_ids))
    if len(ordered) < 2:
        return [set(ordered)]
    fold_count = min(max(2, n_folds), len(ordered))
    folds = [set() for _ in range(fold_count)]
    for index, episode_id in enumerate(ordered):
        folds[index % fold_count].add(episode_id)
    return [fold for fold in folds if fold]


def correction_grid() -> tuple[CorrectionParams, ...]:
    return tuple(
        CorrectionParams(inertia_scale, inertia_tau, homeostatic_scale, debt_scale)
        for inertia_scale in INERTIA_SCALES
        for inertia_tau in INERTIA_TAUS
        for homeostatic_scale in HOMEOSTATIC_SCALES
        for debt_scale in DEBT_SCALES
    )


def select_correction(
    rows: Sequence[FitObservation],
    harmonics: int,
    candidates: Sequence[CorrectionParams] | None = None,
    ridge_strength: float = 0.0,
    inner_folds: int = 3,
) -> CorrectionParams:
    candidates = tuple(candidates or correction_grid())
    folds = _group_folds([row.episode_id for row in rows], inner_folds)
    best: tuple[float, float, CorrectionParams] | None = None
    for candidate in candidates:
        errors: list[float] = []
        for holdout in folds:
            training = [row for row in rows if row.episode_id not in holdout]
            testing = [
                row for row in rows
                if row.episode_id in holdout and row.original
            ]
            if not training or not testing:
                continue
            fit = fit_hybrid(training, harmonics, candidate, ridge_strength)
            errors.extend(
                (
                    predict_hybrid(fit, testing)
                    - np.asarray([row.energy for row in testing], dtype=float)
                ).tolist()
            )
        if not errors:
            continue
        mae = float(np.mean(np.abs(errors)))
        rmse = float(np.sqrt(np.mean(np.square(errors))))
        score = (mae, rmse, candidate)
        if best is None or score < best:
            best = score
    if best is None:
        raise ValueError("No valid inner folds for correction selection")
    return best[2]


def select_ridge_strength(
    rows: Sequence[FitObservation],
    correction: CorrectionParams,
    strengths: Sequence[float] = RIDGE_STRENGTHS,
    inner_folds: int = 3,
) -> float:
    folds = _group_folds([row.episode_id for row in rows], inner_folds)
    best: tuple[float, float, float] | None = None
    for strength in strengths:
        errors: list[float] = []
        for holdout in folds:
            training = [row for row in rows if row.episode_id not in holdout]
            testing = [
                row for row in rows
                if row.episode_id in holdout and row.original
            ]
            if not training or not testing:
                continue
            fit = fit_hybrid(training, 3, correction, float(strength))
            errors.extend(
                (
                    predict_hybrid(fit, testing)
                    - np.asarray([row.energy for row in testing], dtype=float)
                ).tolist()
            )
        if errors:
            score = (
                float(np.mean(np.abs(errors))),
                float(np.sqrt(np.mean(np.square(errors)))),
                float(strength),
            )
            if best is None or score < best:
                best = score
    if best is None:
        raise ValueError("No valid inner folds for ridge selection")
    return best[2]


def _reference_observations(fit: HybridFit) -> list[FitObservation]:
    wake = datetime(2026, 1, 1, 7, 0)
    episode = WakeEpisode(
        episode_id="reference",
        wake=wake,
        end=wake.replace(hour=23),
        main_sleep_duration=REFERENCE_SLEEP_HOURS,
        debt_hours=0.0,
        nap_intervals=(),
        slots=[],
        complete=True,
    )
    rows: list[FitObservation] = []
    for quarter in range(7 * 4, 23 * 4 + 1):
        point = wake.replace(hour=0, minute=0) + timedelta(minutes=quarter * 15)
        process_delta, hours_since_last_wake = _process_features(
            (point - wake).total_seconds() / 3600.0,
            REFERENCE_SLEEP_HOURS,
            (),
        )
        rows.append(
            FitObservation(
                episode_id=episode.episode_id,
                datetime=point,
                clock_hour=quarter / 4.0,
                energy=0.0,
                original=True,
                wake_hour=7.0,
                sleep_duration=REFERENCE_SLEEP_HOURS,
                debt_hours=0.0,
                nap_intervals=(),
                process_delta=process_delta,
                hours_since_last_wake=hours_since_last_wake,
                debt_load=0.0,
            )
        )
    return rows


def peak_dip_peak_summary(hours: Sequence[float], values: Sequence[float]) -> dict:
    points = list(zip(hours, values))
    morning = [point for point in points if 9.0 <= point[0] <= 14.0]
    afternoon = [point for point in points if 13.0 <= point[0] <= 18.0]
    evening = [point for point in points if 17.0 <= point[0] <= 23.0]
    if not morning or not afternoon or not evening:
        return {"eligible": False, "passes": False}
    first_peak = max(morning, key=lambda point: point[1])
    dip_candidates = [point for point in afternoon if point[0] > first_peak[0]]
    if not dip_candidates:
        return {"eligible": False, "passes": False}
    dip = min(dip_candidates, key=lambda point: point[1])
    rebound_candidates = [point for point in evening if point[0] > dip[0]]
    if not rebound_candidates:
        return {"eligible": False, "passes": False}
    rebound = max(rebound_candidates, key=lambda point: point[1])
    dip_prominence = first_peak[1] - dip[1]
    rebound_prominence = rebound[1] - dip[1]
    return {
        "eligible": True,
        "passes": dip_prominence >= 4.0 and rebound_prominence >= 4.0,
        "first_peak": {"hour": first_peak[0], "value": first_peak[1]},
        "dip": {"hour": dip[0], "value": dip[1]},
        "rebound": {"hour": rebound[0], "value": rebound[1]},
        "afternoon_dip_prominence": dip_prominence,
        "evening_rebound_prominence": rebound_prominence,
    }


def reference_shape(fit: HybridFit) -> dict:
    rows = _reference_observations(fit)
    predictions = predict_hybrid(fit, rows)
    summary = peak_dip_peak_summary(
        [row.clock_hour for row in rows],
        predictions.tolist(),
    )
    summary["minimum"] = float(np.min(predictions))
    summary["maximum"] = float(np.max(predictions))
    summary["range"] = float(np.max(predictions) - np.min(predictions))
    return summary


def episode_shape(fit: HybridFit, episode: WakeEpisode) -> dict:
    rows: list[FitObservation] = []
    point = episode.wake
    while point < episode.end and point < episode.wake + timedelta(hours=24):
        elapsed = (point - episode.wake).total_seconds() / 3600.0
        if not any(start <= elapsed < end for start, end in episode.nap_intervals):
            process_delta, hours_since_last_wake = _process_features(
                elapsed,
                episode.main_sleep_duration,
                episode.nap_intervals,
            )
            rows.append(
                FitObservation(
                    episode_id=episode.episode_id,
                    datetime=point,
                    clock_hour=point.hour + point.minute / 60.0,
                    energy=0.0,
                    original=True,
                    wake_hour=episode.wake_hour,
                    sleep_duration=episode.main_sleep_duration,
                    debt_hours=episode.debt_hours,
                    nap_intervals=episode.nap_intervals,
                    process_delta=min(
                        HOMEOSTATIC_DELTA_MAX,
                        max(HOMEOSTATIC_DELTA_MIN, process_delta),
                    ),
                    hours_since_last_wake=hours_since_last_wake,
                    debt_load=1.0 - math.exp(
                        -episode.debt_hours / DEBT_SATURATION_HOURS
                    ),
                )
            )
        point += timedelta(minutes=15)
    predictions = predict_hybrid(fit, rows)
    return peak_dip_peak_summary(
        [row.clock_hour for row in rows],
        predictions.tolist(),
    )


def evaluate_promotion(
    n_qualified_episodes: int,
    baseline_metrics: dict,
    candidate_metrics: dict,
    episode_win_rate: float,
    reference: dict,
    heldout_shape_rate: float,
) -> dict:
    gates = {
        "at_least_21_qualified_wake_episodes": n_qualified_episodes >= 21,
        "outer_cv_mae_improves_by_at_least_1": (
            baseline_metrics["mae"] - candidate_metrics["mae"] >= 1.0
        ),
        "outer_cv_rmse_does_not_regress": (
            candidate_metrics["rmse"] <= baseline_metrics["rmse"] + 1e-12
        ),
        "wins_at_least_60_percent_of_episodes": episode_win_rate >= 0.60,
        "reference_peak_dip_peak_prominence": bool(reference.get("passes", False)),
        "heldout_shape_reproduction_at_least_60_percent": heldout_shape_rate >= 0.60,
    }
    return {"passed": all(gates.values()), "gates": gates}


def _family_cv(
    episodes: Sequence[WakeEpisode],
    rows: Sequence[FitObservation],
    harmonics: int,
    outer_folds: int,
    correction_candidates: Sequence[CorrectionParams] | None,
    ridge_strengths: Sequence[float],
) -> dict:
    folds = _group_folds([episode.episode_id for episode in episodes], outer_folds)
    all_errors: list[float] = []
    fold_reports: list[dict] = []
    episode_errors: dict[str, list[float]] = {}
    shape_reports: dict[str, dict] = {}
    episode_by_id = {episode.episode_id: episode for episode in episodes}

    for fold_index, holdout in enumerate(folds, start=1):
        training = [row for row in rows if row.episode_id not in holdout]
        testing = [
            row for row in rows if row.episode_id in holdout and row.original
        ]
        correction = select_correction(
            training,
            harmonics,
            candidates=correction_candidates,
            ridge_strength=1.0 if harmonics == 3 else 0.0,
        )
        ridge = (
            select_ridge_strength(training, correction, strengths=ridge_strengths)
            if harmonics == 3 else 0.0
        )
        fit = fit_hybrid(training, harmonics, correction, ridge)
        actual = np.asarray([row.energy for row in testing], dtype=float)
        predicted = predict_hybrid(fit, testing)
        errors = predicted - actual
        all_errors.extend(errors.tolist())
        fold_reports.append(
            {
                "fold": fold_index,
                "held_out_episodes": sorted(holdout),
                "metrics": metric_summary(actual, predicted),
                "correction": asdict(correction),
                "ridge_strength": ridge,
            }
        )
        for episode_id in holdout:
            indices = [
                index for index, row in enumerate(testing)
                if row.episode_id == episode_id
            ]
            episode_errors[episode_id] = [float(errors[index]) for index in indices]
            shape_reports[episode_id] = episode_shape(fit, episode_by_id[episode_id])

    errors_array = np.asarray(all_errors, dtype=float)
    metrics = {
        "n": int(len(errors_array)),
        "mae": float(np.mean(np.abs(errors_array))),
        "rmse": float(np.sqrt(np.mean(np.square(errors_array)))),
        "bias": float(np.mean(errors_array)),
    }
    return {
        "harmonics": harmonics,
        "metrics": metrics,
        "folds": fold_reports,
        "episode_errors": episode_errors,
        "shape_reports": shape_reports,
    }


def _baseline_cv(
    episodes: Sequence[WakeEpisode],
    rows: Sequence[FitObservation],
    outer_folds: int,
) -> dict:
    folds = _group_folds([episode.episode_id for episode in episodes], outer_folds)
    original = [row for row in rows if row.original]
    actual = np.asarray([row.energy for row in original], dtype=float)
    predicted = predict_m8(original)
    fold_reports: list[dict] = []
    for fold_index, holdout in enumerate(folds, start=1):
        indices = [
            index for index, row in enumerate(original)
            if row.episode_id in holdout
        ]
        fold_reports.append(
            {
                "fold": fold_index,
                "held_out_episodes": sorted(holdout),
                "metrics": metric_summary(actual[indices], predicted[indices]),
            }
        )
    return {"metrics": metric_summary(actual, predicted), "folds": fold_reports}


def fit_and_validate(
    csv_path: Path,
    min_logged: int = 20,
    max_gap_slots: int = 2,
    outer_folds: int = 5,
    correction_candidates: Sequence[CorrectionParams] | None = None,
    ridge_strengths: Sequence[float] = RIDGE_STRENGTHS,
) -> tuple[dict, dict]:
    slots = read_slots(csv_path)
    episodes, rows, coverage = prepare_dataset(slots, min_logged, max_gap_slots)
    if len(episodes) < 2:
        raise RuntimeError("Need at least two qualified wake episodes for validation")
    original_rows = [row for row in rows if row.original]
    baseline_prediction = predict_m8(original_rows)
    baseline = _baseline_cv(episodes, rows, outer_folds)
    baseline_metrics = baseline["metrics"]

    two = _family_cv(
        episodes, rows, 2, outer_folds, correction_candidates, ridge_strengths
    )
    three = _family_cv(
        episodes, rows, 3, outer_folds, correction_candidates, ridge_strengths
    )
    selected = min(
        (two, three),
        key=lambda report: (
            report["metrics"]["mae"],
            report["metrics"]["rmse"],
            report["harmonics"],
        ),
    )

    final_correction = select_correction(
        rows,
        selected["harmonics"],
        candidates=correction_candidates,
        ridge_strength=1.0 if selected["harmonics"] == 3 else 0.0,
    )
    final_ridge = (
        select_ridge_strength(rows, final_correction, strengths=ridge_strengths)
        if selected["harmonics"] == 3 else 0.0
    )
    final_fit = fit_hybrid(
        rows,
        selected["harmonics"],
        final_correction,
        final_ridge,
    )

    baseline_episode_errors: dict[str, list[float]] = {}
    for row, prediction in zip(original_rows, baseline_prediction):
        baseline_episode_errors.setdefault(row.episode_id, []).append(
            float(prediction - row.energy)
        )
    wins = 0
    per_episode: list[dict] = []
    for episode in episodes:
        baseline_error = baseline_episode_errors[episode.episode_id]
        candidate_error = selected["episode_errors"][episode.episode_id]
        baseline_mae = float(np.mean(np.abs(baseline_error)))
        candidate_mae = float(np.mean(np.abs(candidate_error)))
        won = candidate_mae < baseline_mae
        wins += int(won)
        per_episode.append(
            {
                "episode_id": episode.episode_id,
                "logged_points": episode.logged_count,
                "baseline_mae": baseline_mae,
                "candidate_mae": candidate_mae,
                "candidate_wins": won,
            }
        )
    win_rate = wins / len(episodes)

    reference = reference_shape(final_fit)
    eligible_shapes = [
        report for report in selected["shape_reports"].values()
        if report.get("eligible", False)
    ]
    shape_rate = (
        sum(bool(report.get("passes", False)) for report in eligible_shapes)
        / len(eligible_shapes)
        if eligible_shapes else 0.0
    )
    promotion = evaluate_promotion(
        len(episodes),
        baseline_metrics,
        selected["metrics"],
        win_rate,
        reference,
        shape_rate,
    )

    params = {
        "circadian_coefs": [float(value) for value in final_fit.coefs],
        "harmonics": final_fit.harmonics,
        "reference_sleep_hours": REFERENCE_SLEEP_HOURS,
        "reference_peak_clock": 20.0,
        "max_phase_shift_hours": MAX_PHASE_SHIFT_HOURS,
        "tau_wake": TAU_WAKE,
        "tau_sleep": TAU_SLEEP,
        "inertia_tau": final_fit.correction.inertia_tau,
        "homeostatic_scale": final_fit.correction.homeostatic_scale,
        "debt_scale": final_fit.correction.debt_scale,
        "debt_saturation_hours": DEBT_SATURATION_HOURS,
        "inertia_scale": final_fit.correction.inertia_scale,
        "process_s_min": PROCESS_S_MIN,
        "process_s_max": PROCESS_S_MAX,
        "homeostatic_delta_min": HOMEOSTATIC_DELTA_MIN,
        "homeostatic_delta_max": HOMEOSTATIC_DELTA_MAX,
        "third_harmonic_ridge": final_fit.ridge_strength,
    }
    sha256 = hashlib.sha256(csv_path.read_bytes()).hexdigest()
    candidate = {
        "schema_version": SCHEMA_VERSION,
        "model_id": MODEL_ID,
        "status": "promotion_eligible" if promotion["passed"] else "provisional",
        "production_model_id": "M8_adaptive_sleep",
        "params": params,
        "training": {
            "csv": str(csv_path.resolve()),
            "csv_sha256": sha256,
            "min_logged_per_wake_episode": min_logged,
            "max_interpolated_gap_slots": max_gap_slots,
            "n_qualified_wake_episodes": len(episodes),
            "qualified_episode_ids": [episode.episode_id for episode in episodes],
            "n_original_points": len(original_rows),
            "n_interpolated_points": sum(not row.original for row in rows),
            "episode_weighting": "equal total weight per wake episode",
            "validation": "outer grouped cross-validation; complete wake episodes held out",
        },
        "promotion": promotion,
    }
    validation = {
        "schema_version": SCHEMA_VERSION,
        "model_id": MODEL_ID,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "common_sample": coverage,
        "baseline_m8": baseline,
        "two_harmonic": two,
        "three_harmonic_ridge": three,
        "selected_harmonics": selected["harmonics"],
        "selected_metrics": selected["metrics"],
        "per_episode": per_episode,
        "episode_win_rate": win_rate,
        "reference_awake_extrema": reference,
        "eligible_heldout_shape_episodes": len(eligible_shapes),
        "heldout_shape_reproduction_rate": shape_rate,
        "promotion": promotion,
    }
    return candidate, validation


def validation_markdown(candidate: dict, report: dict) -> str:
    baseline = report["baseline_m8"]["metrics"]
    selected = report["selected_metrics"]
    promotion = report["promotion"]
    lines = [
        "# M9 Awake Hybrid Validation",
        "",
        f"- Status: **{candidate['status']}**",
        f"- Qualified wake episodes: **{candidate['training']['n_qualified_wake_episodes']}**",
        f"- Common original ratings: **{candidate['training']['n_original_points']}**",
        f"- Selected family: **{report['selected_harmonics']} harmonics**",
        f"- M8 outer-CV/common-sample MAE/RMSE/bias: **{baseline['mae']:.2f} / {baseline['rmse']:.2f} / {baseline['bias']:.2f}**",
        f"- M9 outer-CV MAE/RMSE/bias: **{selected['mae']:.2f} / {selected['rmse']:.2f} / {selected['bias']:.2f}**",
        f"- Per-episode win rate: **{report['episode_win_rate']:.1%}**",
        f"- Held-out peak–dip–peak reproduction: **{report['heldout_shape_reproduction_rate']:.1%}**",
        "",
        "## Promotion gates",
        "",
    ]
    for name, passed in promotion["gates"].items():
        lines.append(f"- {'PASS' if passed else 'FAIL'} — {name.replace('_', ' ')}")
    lines.extend(["", "## Fold metrics", ""])
    chosen = (
        report["three_harmonic_ridge"]
        if report["selected_harmonics"] == 3
        else report["two_harmonic"]
    )
    lines.append("| Fold | Held-out episodes | M8 MAE | M9 MAE | M9 RMSE | M9 Bias |")
    lines.append("|---:|---:|---:|---:|---:|---:|")
    baseline_folds = report["baseline_m8"]["folds"]
    for fold, baseline_fold in zip(chosen["folds"], baseline_folds):
        metrics = fold["metrics"]
        lines.append(
            f"| {fold['fold']} | {len(fold['held_out_episodes'])} | "
            f"{baseline_fold['metrics']['mae']:.2f} | {metrics['mae']:.2f} | "
            f"{metrics['rmse']:.2f} | {metrics['bias']:.2f} |"
        )
    lines.extend(
        [
            "",
            "Production remains on committed M8 unless every gate passes and the "
            "separate explicit promotion command is run.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    repo_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=repo_root / "Alertness_Master_Log.csv")
    parser.add_argument("--analysis-dir", type=Path, default=repo_root / "analysis")
    parser.add_argument("--min-logged", type=int, default=20)
    parser.add_argument("--max-gap", type=int, default=2)
    parser.add_argument("--outer-folds", type=int, default=5)
    args = parser.parse_args()

    candidate, validation = fit_and_validate(
        args.csv,
        min_logged=args.min_logged,
        max_gap_slots=args.max_gap,
        outer_folds=args.outer_folds,
    )
    args.analysis_dir.mkdir(parents=True, exist_ok=True)
    candidate_path = args.analysis_dir / "m9_awake_hybrid_candidate.json"
    validation_path = args.analysis_dir / "m9_awake_hybrid_validation.json"
    markdown_path = args.analysis_dir / "m9_awake_hybrid_validation.md"
    candidate_path.write_text(json.dumps(candidate, indent=2) + "\n", encoding="utf-8")
    validation_path.write_text(json.dumps(validation, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(validation_markdown(candidate, validation), encoding="utf-8")
    selected = validation["selected_metrics"]
    baseline = validation["baseline_m8"]["metrics"]
    print(
        f"M9 provisional: {candidate['training']['n_qualified_wake_episodes']} qualified "
        f"episodes; M8 MAE {baseline['mae']:.2f}, candidate MAE {selected['mae']:.2f}; "
        f"promotion={'PASS' if candidate['promotion']['passed'] else 'HOLD'}"
    )


if __name__ == "__main__":
    main()
