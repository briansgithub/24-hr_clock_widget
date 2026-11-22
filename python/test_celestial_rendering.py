import datetime
import math
import unittest
from unittest.mock import patch

from clock_widget import (
    ClockWidget,
    MOON_LIT_COLOR,
    MOON_SHADOW_COLOR,
    SYNODIC_MONTH_DAYS,
)


class RecordingCanvas:
    def __init__(self):
        self.items = []

    def _record(self, kind, coordinates, options):
        item_id = len(self.items) + 1
        self.items.append({
            "id": item_id,
            "kind": kind,
            "coordinates": tuple(coordinates),
            "options": dict(options),
        })
        return item_id

    def create_oval(self, *coordinates, **options):
        return self._record("oval", coordinates, options)

    def create_arc(self, *coordinates, **options):
        return self._record("arc", coordinates, options)


class CelestialRenderingTests(unittest.TestCase):
    def setUp(self):
        self.widget = ClockWidget.__new__(ClockWidget)
        self.widget.canvas = RecordingCanvas()

    def test_outer_orbit_matches_last_working_python_layout(self):
        geometry = self.widget._celestial_marker_geometry(
            center_x=115.0,
            center_y=120.0,
            radius=63.0,
            sun_rad=0.0,
            moon_rad=math.pi / 2.0,
        )

        self.assertEqual(geometry["icon_size"], 12)
        self.assertAlmostEqual(geometry["orbit_radius"], 77.0)
        self.assertAlmostEqual(geometry["sun_radius"], 6.25)
        self.assertAlmostEqual(geometry["moon_radius"], 6.25)
        self.assertEqual(geometry["sun_center"], (192.0, 120.0))
        self.assertAlmostEqual(geometry["moon_center"][0], 115.0)
        self.assertAlmostEqual(geometry["moon_center"][1], 43.0)
        self.assertGreater(
            geometry["orbit_radius"] - geometry["sun_radius"],
            63.0,
            "markers must remain fully outside the clock face",
        )

    def test_marker_bounds_fit_the_default_canvas_padding(self):
        width, height = 230.0, 240.0
        center_x, center_y, radius = width / 2.0, height / 2.0, 63.0
        for angle in (0.0, math.pi / 2.0, math.pi, 3.0 * math.pi / 2.0):
            geometry = self.widget._celestial_marker_geometry(
                center_x, center_y, radius, angle, angle
            )
            for marker_center, r_key in (
                (geometry["sun_center"], "sun_radius"),
                (geometry["moon_center"], "moon_radius")
            ):
                x, y = marker_center
                r = geometry[r_key]
                self.assertGreaterEqual(x - r, 0.0)
                self.assertLessEqual(x + r, width)
                self.assertGreaterEqual(y - r, 0.0)
                self.assertLessEqual(y + r, height)

    def test_python_and_android_angle_conventions_are_equivalent(self):
        center_y = 120.0
        orbit_radius = 77.0
        mapped_angle = math.radians(37.0)
        python_y = center_y - orbit_radius * math.sin(mapped_angle)
        # Android's CelestialManager negates the mapped angle and its renderer
        # adds screen-space sine, producing the same vertical coordinate.
        android_y = center_y + orbit_radius * math.sin(-mapped_angle)
        self.assertAlmostEqual(python_y, android_y)

    def test_sun_colors_match_android_renderer_stops(self):
        self.assertEqual(
            self.widget._interpolate_sun_color(20.0),
            ("#ffd700", 255, "#ffa500"),
        )
        self.assertEqual(
            self.widget._interpolate_sun_color(0.0),
            ("#ff4500", 255, "#ff2800"),
        )
        self.assertEqual(
            self.widget._interpolate_sun_color(-8.0),
            ("#ffd700", 255, "#ffd700"),
        )

    def test_every_moon_phase_matches_original_objects_and_colors(self):
        for phase_fraction in (0.0, 0.25, 0.5, 0.75, 0.995):
            with self.subTest(phase_fraction=phase_fraction):
                self.widget.canvas = RecordingCanvas()
                self.widget._draw_celestial_markers(
                    115.0, 120.0, 63.0,
                    sun_rad=0.0,
                    moon_rad=math.pi,
                    moon_phase_value=phase_fraction * SYNODIC_MONTH_DAYS,
                    sun_elevation=20.0,
                )
                items = self.widget.canvas.items
                moon_surface_colors = {
                    item["options"].get("fill")
                    for item in items[1:]
                    if item["options"].get("fill")
                }
                moon_outlines = {
                    item["options"].get("outline")
                    for item in items[1:]
                    if item["options"].get("outline")
                }

                self.assertIn(MOON_SHADOW_COLOR, moon_surface_colors)
                if 0.01 < phase_fraction < 0.99:
                    self.assertIn(MOON_LIT_COLOR, moon_surface_colors)
                    self.assertEqual(len(items), 4)  # sun + base + arc + ellipse
                else:
                    self.assertEqual(len(items), 2)  # sun + original dark moon
                self.assertEqual(moon_outlines, set())

    def test_waxing_and_waning_lit_sides_match_android(self):
        expected_arc_starts = {0.25: -90, 0.75: 90}
        for phase_fraction, expected_start in expected_arc_starts.items():
            with self.subTest(phase_fraction=phase_fraction):
                self.widget.canvas = RecordingCanvas()
                self.widget._draw_celestial_markers(
                    115.0, 120.0, 63.0,
                    sun_rad=0.0,
                    moon_rad=math.pi,
                    moon_phase_value=phase_fraction * SYNODIC_MONTH_DAYS,
                    sun_elevation=0.0,
                )
                phase_arcs = [
                    item for item in self.widget.canvas.items
                    if item["kind"] == "arc"
                ]
                self.assertEqual(len(phase_arcs), 1)
                self.assertEqual(
                    phase_arcs[0]["options"]["start"], expected_start
                )

    def test_fallback_phase_and_color_inputs_advance_with_time(self):
        epoch = datetime.datetime(2000, 1, 6, 18, 14, tzinfo=datetime.timezone.utc)
        noon = 12.0
        first = self.widget._fallback_celestial_positions(noon, epoch)
        quarter = self.widget._fallback_celestial_positions(
            noon, epoch + datetime.timedelta(days=SYNODIC_MONTH_DAYS / 4.0)
        )

        self.assertAlmostEqual(first[2], 0.0)
        self.assertAlmostEqual(quarter[2], SYNODIC_MONTH_DAYS / 4.0)
        self.assertNotAlmostEqual(first[1], quarter[1])
        self.assertGreater(first[3], 0.0)
        midnight = self.widget._fallback_celestial_positions(0.0, epoch)
        self.assertLess(midnight[3], 0.0)

    def test_astral_position_path_uses_observer_elevation_and_phase(self):
        class FakeObserver:
            def __init__(self, latitude, longitude):
                self.latitude = latitude
                self.longitude = longitude

        def fake_sun_elevation(_observer, moment):
            hour = moment.timestamp() / 3600.0
            return 45.0 * math.sin(hour * math.pi / 12.0)

        def fake_moon_elevation(_observer, moment):
            hour = moment.timestamp() / 3600.0
            return 35.0 * math.sin((hour - 5.0) * math.pi / 12.0)

        self.widget.lat = 40.7128
        self.widget.lon = -74.0060
        # Location refresh explicitly invalidates this cache with None.
        self.widget._extremes_cache = None
        dependencies = (
            FakeObserver,
            None,
            fake_sun_elevation,
            lambda _date: 10.0,
            fake_moon_elevation,
        )
        with patch("clock_widget._load_astral_dependencies", return_value=dependencies):
            sun_rad, moon_rad, phase_days, sun_elevation = (
                self.widget._get_celestial_positions(12.0)
            )

        self.assertTrue(math.isfinite(sun_rad))
        self.assertTrue(math.isfinite(moon_rad))
        self.assertEqual(phase_days, 10.0)
        self.assertTrue(math.isfinite(sun_elevation))
        self.assertIn("obs", self.widget._extremes_cache)

    def test_solar_irradiance_rebuilds_explicitly_invalidated_peak_cache(self):
        class FakeObserver:
            def __init__(self, latitude, longitude):
                self.latitude = latitude
                self.longitude = longitude

        self.widget.lat = 40.7128
        self.widget.lon = -74.0060
        self.widget._solar_peak_cache = None
        dependencies = (
            FakeObserver,
            None,
            lambda _observer, _moment: 30.0,
            None,
            None,
        )

        with patch("clock_widget._load_astral_dependencies", return_value=dependencies):
            brightness = self.widget._get_solar_irradiance()

        self.assertEqual(brightness, 255)
        self.assertEqual(self.widget._solar_peak_cache["peak"], 30.0)


if __name__ == "__main__":
    unittest.main()
