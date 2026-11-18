import unittest
from types import SimpleNamespace

from clock_widget import ClockWidget


class FakeWindow:
    def __init__(self):
        self.geometry_calls = []

    def geometry(self, specification):
        self.geometry_calls.append(specification)


class FakeRoot(FakeWindow):
    def __init__(self):
        super().__init__()
        self.after_calls = []
        self.cancelled = []
        self.idle_callbacks = []

    def after(self, delay, callback):
        callback_id = f"after-{len(self.after_calls) + 1}"
        self.after_calls.append((callback_id, delay, callback))
        return callback_id

    def after_cancel(self, callback_id):
        self.cancelled.append(callback_id)

    def after_idle(self, callback):
        self.idle_callbacks.append(callback)

    def run_next_timer(self):
        _callback_id, _delay, callback = self.after_calls.pop(0)
        callback()


class DragCoalescingTests(unittest.TestCase):
    def setUp(self):
        self.widget = ClockWidget.__new__(ClockWidget)
        self.widget.root = FakeRoot()
        self.widget.controls_window = FakeWindow()
        self.widget.bedtime_countdown_window = None
        self.widget.is_controls_visible = False
        self.widget._window_rects = {}
        self.widget._drag_flush_id = None
        self.widget._pending_drag_geometry = None
        self.widget._resize_data = None
        self.widget._drag_data = {}

    def test_many_motion_events_apply_only_the_latest_frame(self):
        self.widget._queue_drag_geometry(
            self.widget.root, 10, 20, 230, 240
        )
        self.widget._queue_drag_geometry(
            self.widget.root, 20, 30, 230, 240
        )
        self.widget._queue_drag_geometry(
            self.widget.root, 30, 40, 230, 240
        )

        self.assertEqual(len(self.widget.root.after_calls), 1)
        self.assertEqual(self.widget.root.geometry_calls, [])

        self.widget.root.run_next_timer()

        self.assertEqual(self.widget.root.geometry_calls, ["+30+40"])
        self.assertEqual(self.widget._window_rects["root"], (30, 40, 230, 240))

    def test_attached_settings_moves_once_with_the_clock_frame(self):
        self.widget.is_controls_visible = True

        self.widget._queue_drag_geometry(
            self.widget.root,
            100,
            150,
            230,
            240,
            follow_controls=True,
            controls_size=(560, 680),
        )
        self.widget.root.run_next_timer()

        self.assertEqual(self.widget.root.geometry_calls, ["+100+150"])
        self.assertEqual(
            self.widget.controls_window.geometry_calls,
            ["+340+150"],
        )
        self.assertEqual(
            self.widget._window_rects["controls"],
            (340, 150, 560, 680),
        )

    def test_release_flushes_the_exact_latest_pointer_position(self):
        self.widget._canvas_control_gesture = False
        self.widget._canvas_control_gesture_token = None
        self.widget._drag_in_progress = True
        self.widget._resize_redraw_id = None
        self.widget._drag_data.update({
            "x": 100,
            "y": 100,
            "wx": 20,
            "wy": 30,
            "width": 230,
            "height": 240,
            "window": self.widget.root,
            "moved": False,
            "follow_controls": False,
            "controls_size": None,
        })

        self.widget.on_drag_motion(SimpleNamespace(x_root=111, y_root=117))
        self.assertEqual(self.widget.root.geometry_calls, [])

        self.widget.on_drag_end()

        self.assertEqual(self.widget.root.geometry_calls, ["+31+47"])
        self.assertEqual(self.widget.root.cancelled, ["after-1"])
        self.assertFalse(self.widget._drag_in_progress)
        self.assertEqual(len(self.widget.root.idle_callbacks), 1)


if __name__ == "__main__":
    unittest.main()
