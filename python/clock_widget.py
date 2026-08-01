import tkinter as tk
import os
from tkinter import ttk
import math
import datetime
import threading
import urllib.request
import json
import time
import queue
from concurrent.futures import ThreadPoolExecutor
from energy_logic import EnergyCurve, sleep_model_context

_ASTRAL_DEPENDENCIES = None
SYNODIC_MONTH_DAYS = 29.530588
MOON_SHADOW_COLOR = "#444444"
MOON_LIT_COLOR = "#E0E0E0"


def _load_astral_dependencies():
    global _ASTRAL_DEPENDENCIES
    if _ASTRAL_DEPENDENCIES is None:
        from astral import Observer
        from astral.sun import sun, elevation as sun_elevation
        from astral.moon import phase, elevation as moon_elevation
        _ASTRAL_DEPENDENCIES = Observer, sun, sun_elevation, phase, moon_elevation
    return _ASTRAL_DEPENDENCIES

class Tooltip:
    def __init__(self, widget, text, tag=None):
        self.widget = widget
        self.text = text
        self.tag = tag
        self.tip_window = None
        if tag is None:
            self.widget.bind("<Enter>", self.show_tip)
            self.widget.bind("<Leave>", self.hide_tip)
        else:
            self.widget.tag_bind(tag, "<Enter>", self.show_tip)
            self.widget.tag_bind(tag, "<Leave>", self.hide_tip)

    def show_tip(self, event=None):
        if self.tip_window or not self.text:
            return
        if self.tag is None:
            x = self.widget.winfo_rootx() + 20
            y = self.widget.winfo_rooty() + self.widget.winfo_height() + 5
        else:
            bounds = self.widget.bbox(self.tag)
            if not bounds:
                return
            x = self.widget.winfo_rootx() + bounds[0] + 20
            y = self.widget.winfo_rooty() + bounds[3] + 5
        self.tip_window = tw = tk.Toplevel(self.widget)
        tw.wm_overrideredirect(True)
        tw.wm_geometry(f"+{x}+{y}")
        tw.attributes("-topmost", True)
        label = tk.Label(tw, text=self.text, justify=tk.LEFT,
                         background="#2a2a2a", foreground="white", relief=tk.SOLID, borderwidth=1,
                         font=("Arial", "8"), padx=5, pady=3)
        label.pack()

    def hide_tip(self, event=None):
        tw = self.tip_window
        self.tip_window = None
        if tw:
            tw.destroy()

class ClockWidget:
    FACE_PADDING = 52
    DRAG_FRAME_MS = 12
    UI_BG = "#181b21"
    UI_SURFACE = "#22262f"
    UI_SURFACE_ALT = "#292e39"
    UI_BORDER = "#373e4b"
    UI_TEXT = "#f3f5f7"
    UI_MUTED = "#9ca6b5"
    UI_ACCENT = "#55d6be"
    UI_ACCENT_HOVER = "#73e2cc"
    UI_DANGER = "#ff7b83"
    # -------------------------------------------------------------------------
    # Z-INDEX / DRAWING ORDER
    # -------------------------------------------------------------------------
    # Rearrange this list to change which elements are drawn on top.
    # Elements later in the list are drawn ON TOP of earlier elements.
    DRAW_ORDER = [
        "background_face",
        "night_shading",
        "sun_and_moon",
        "sleep_arc",
        "calendar_events",
        "perimeter_line",
        "energy_curve",
        "ticks_and_numbers",
        "manual_wake_tick",
        "solar_circle",
        "sleep_debt_text",
        "reauth_indicators",
        "energy_indicators",
        "clock_hand"
    ]

    EVENT_COLORS = [
        "#FF5733", "#33FF57", "#3357FF", "#F333FF", "#FF33A1", "#33FFF5",
        "#F5FF33", "#FF8633", "#8633FF", "#33FF86", "#FF3333", "#3333FF",
        "#33FF33", "#FFFF33", "#FF33FF", "#33FFFF", "#FF9933", "#99FF33",
        "#3399FF", "#FF3399", "#9933FF", "#33FF99", "#FF6633", "#66FF33"
    ]

    # --- CONFIGURE YOUR FITBIT CREDENTIALS HERE ---
    # Secrets will be loaded from fitbit_config.json
    FITBIT_CLIENT_ID = None
    FITBIT_CLIENT_SECRET = None
    # -- PERSONAL GOALS ----------------------------
    # Your target time spent in bed (9h 45m = 9.75)
    # The app will automatically calculate your 'Sleep Need'
    # by multiplying this by your actual efficiency.
    # ----------------------------------------------

    def __init__(self, root):
        self._launch_started = time.perf_counter()
        self.root = root
        self.root.title("24h Clock")
        self._control_widgets = []
       
        window_width = 240
        window_height = 240
        screen_width = self.root.winfo_screenwidth()
        screen_height = self.root.winfo_screenheight()
        
        # Position: upper left, part of the way down
        initial_x = 5
        initial_y = int(screen_height / 8)
        self.root.geometry(f"{window_width}x{window_height}+{initial_x}+{initial_y}")

        self.root.minsize(120, 150)
        self.root.configure(bg="#2b2b2b")
       
        self.always_on_top = tk.BooleanVar(value=True)
        self.show_numbers = tk.BooleanVar(value=False)
        self.show_sleep = tk.BooleanVar(value=True)
        self.show_total_bedtime = tk.BooleanVar(value=True)
        self.show_energy = tk.BooleanVar(value=True)
        self.show_energy_pct = tk.BooleanVar(value=False)
        self.show_sleep_debt = tk.BooleanVar(value=True)
        self.show_sleep_debt_text = tk.BooleanVar(value=True)
        self.normalize_energy = tk.BooleanVar(value=False)
        self.include_naps = tk.BooleanVar(value=True)
        self.show_sun_moon = tk.BooleanVar(value=True)
        self.show_manual_wake = tk.BooleanVar(value=True)
        self.show_bathyphase = tk.BooleanVar(value=True)
        self.show_acrophase = tk.BooleanVar(value=True)
        self.show_grogginess = tk.BooleanVar(value=True)
        self.show_wind_down = tk.BooleanVar(value=True)
        self.show_bedtime_countdown = tk.BooleanVar(value=True)
        self.show_sleep_table = tk.BooleanVar(value=True)
        self.show_calendar = tk.BooleanVar(value=True)
        self.show_sleep_model = tk.BooleanVar(value=False)

        # -- Model Parameters --------------------------
        self.bedtime_goal = tk.DoubleVar(value=9.75)
        self.tau_wake = tk.DoubleVar(value=23.0)
        self.tau_sleep = tk.DoubleVar(value=4.0)
        self.tau_inertia = tk.DoubleVar(value=1.5)
        self.debt_factor = tk.DoubleVar(value=1.0)
        self.circadian_offset = tk.DoubleVar(value=12.0)
        self.use_bathyphase = tk.BooleanVar(value=True)
        self.manual_wake_time = tk.StringVar(value="9:00")
        self.hover_delay_var = tk.StringVar(value="0.2")
        self.timeout_delay_var = tk.StringVar(value="0.3")
        self._settings_refresh_id = None
        self._settings_refresh_needs_debt = False
        self._resize_redraw_id = None
        self._drag_flush_id = None
        self._pending_drag_geometry = None
        self._drag_in_progress = False
        self._window_rects = {}
        self._settings_realized = False
        self._settings_prewarm_pending = False
        self._controls_open_grace_until = 0.0
        self._canvas_control_gesture = False
        self._canvas_control_gesture_token = 0
        self._closing = False

        # A small, long-lived worker pool avoids creating a thread for every
        # refresh. Workers publish immutable results to this queue; only the Tk
        # thread applies UI state.
        self._executor = ThreadPoolExecutor(max_workers=3, thread_name_prefix="clock-widget")
        self._ui_queue = queue.SimpleQueue()
        self._fitbit_operation_lock = threading.Lock()

        self.sleep_settings_file = os.path.join(os.path.dirname(__file__), "sleep_settings.json")
        self.excluded_dates = self._load_sleep_settings()
        self._sync_sleep_inclusion()

        # Load Fitbit Config
        self._load_fitbit_config()

        # Model parameters will redraw on slider release (handled in _add_slider)
        # except for boolean toggles which still trace or use command
        self.use_bathyphase.trace_add("write", lambda *args: self._schedule_settings_refresh())
        self.show_bathyphase.trace_add("write", lambda *args: self._schedule_settings_refresh())
        self.show_acrophase.trace_add("write", lambda *args: self._schedule_settings_refresh())
        self.show_grogginess.trace_add("write", lambda *args: self._schedule_settings_refresh())
        self.show_wind_down.trace_add("write", lambda *args: self._schedule_settings_refresh())
        self.manual_wake_time.trace_add("write", lambda *args: self._schedule_settings_refresh())

        # ── Fitbit Integration ────────────────────────────────────────────────
        # The integration client is imported and created on its first worker
        # request so importing OAuth/browser dependencies cannot delay the clock.
        self.fitbit = None
        self.sleep_hour         = None
        self.wake_hour          = None
        self.sleep_duration     = 7.5   # hours; updated after first Fitbit fetch
        self.sleep_debt_hours   = 0.0   # hours; updated after first Fitbit fetch
        self.bathyphase_hour    = None  # clock hour; None until intraday HR fetched
        self.sleep_efficiency   = None  # 0.0 to 1.0
        self.sleep_need_hours   = 9.0   # placeholder, updated after first Fitbit fetch
        self.last_fitbit_update = None
        self.raw_sleep_logs     = []    # list of raw Fitbit sleep records
        self.active_sleep_date  = None  # the YYYY-MM-DD date we are currently displaying
        self._sleep_table_needs_refresh = True
        self._sleep_table_refresh_scheduled = False
        self._fitbit_refreshing = False
        self._fitbit_refresh_pending = False
        self._fitbit_pending_force = False
        self._fitbit_last_request_monotonic = 0.0
        self._last_hover_refresh_check = 0.0
        self._pending_fitbit_results = None
        self._fitbit_apply_id = None
        self._clock_redraw_id = None
        self._clock_dirty_layers = set()
        self._full_clock_redraw_pending = False
        self._layout_retry_id = None
        self._has_full_clock_draw = False
        
        self.reauth_needed_fitbit = False
        self.reauth_needed_calendar = False
        
        # ── Calendar Integration ──────────────────────────────────────────────
        self.calendar_svc = None # Will be initialized lazily in the background thread
        self.calendar_events = []
        # ─────────────────────────────────────────────────────────────────────
        # ─────────────────────────────────────────────────────────────────────
       
        # Transparency State
        self.transparent_key = "#010203"
        self.solid_bg = "#2b2b2b"
        self.is_clock_transparent = False
        self.is_controls_visible = False
        self.hover_timer = None
        self._last_drawn_minute = None
        self._celestial_cache = None
       
        self.root.attributes("-transparentcolor", self.transparent_key)
        self.root.overrideredirect(True)
        self.root.attributes('-topmost', self.always_on_top.get())
        
        # Tooltip state
        self._calendar_item_map = {}
        self._tooltip_id = None
        self._tooltip_bg_id = None
        self._tooltip_event_item = None
        self._last_tooltip_motion = 0.0
        # Canvas-native controls are shown only while the clock is solid.
        # Keeping them on the canvas avoids another child window and makes the
        # settings action discoverable even when the tray icon is unavailable.
        self._hover_controls_tag = "hover_controls"
       
        # Main Frame
        self.main_frame = tk.Frame(self.root, bg=self.solid_bg)
        self.main_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=0)
       
        # UI Elements
        self.canvas = tk.Canvas(self.main_frame, bg=self.solid_bg, highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)
       
        # Auxiliary windows are created only after the clock's first paint.
        # The settings shell is prewarmed shortly afterward so its first hover
        # remains fast without delaying the primary widget.
        self._control_widgets = []
        self._controls_open_count = 0
        


        self.sunrise_hour = None
        self.sunset_hour = None
       
        self.canvas.bind("<Configure>", self.on_resize)
        self.root.bind("<Configure>", self._cache_window_event, add="+")
       
        # Bind hover events. Lazily created windows bind themselves.
        self.root.bind("<Enter>", self.on_enter)
        self.root.bind("<Leave>", self.on_leave)
       
        # Bind drag events
        for widget in (self.main_frame, self.canvas):
            widget.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.root))
            widget.bind("<B1-Motion>", lambda e: self.on_drag_motion(e))
            widget.bind("<ButtonRelease-1>", self.on_drag_end)
            widget.bind("<ButtonPress-3>", self.on_right_click_start)
            widget.bind("<B3-Motion>", self.on_right_click_motion)
            widget.bind("<ButtonRelease-3>", self.on_right_click_end)
        
        self.canvas.bind("<Motion>", self.on_canvas_motion)

        # Initialize Energy Curve logic
        self.energy_curve = EnergyCurve(self.canvas)

        # Paint from local/default state immediately. Network-backed data starts
        # only after the first visible frame has been queued.
        self.root.withdraw()
        self.update_clock()
        self.root.after(25, self._drain_ui_queue)
        self.root.after_idle(self.show_initial)

    def _log_timing(self, label, started_at):
        elapsed_ms = (time.perf_counter() - started_at) * 1000
        print(f"[ClockWidget:timing] {label}: {elapsed_ms:.1f} ms")

    def _expire_canvas_control_gesture(self, token):
        if token != self._canvas_control_gesture_token:
            return
        self._canvas_control_gesture = False

    def _submit_background(self, callback):
        """Run non-Tk work on the shared bounded worker pool."""
        if self._closing:
            return None
        try:
            future = self._executor.submit(callback)
            future.add_done_callback(self._report_background_failure)
            return future
        except RuntimeError:
            return None

    @staticmethod
    def _report_background_failure(future):
        if future.cancelled():
            return
        try:
            future.result()
        except Exception as exc:
            print(f"[ClockWidget] Background task failed: {exc}")

    def _post_ui(self, callback, *args, **kwargs):
        """Publish a main-thread callback without calling Tk from a worker."""
        if not self._closing:
            self._ui_queue.put((callback, args, kwargs))

    def _drain_ui_queue(self):
        if self._closing:
            return
        for _ in range(64):
            try:
                callback, args, kwargs = self._ui_queue.get_nowait()
            except queue.Empty:
                break
            try:
                callback(*args, **kwargs)
            except tk.TclError:
                if not self._closing:
                    raise
            except Exception as exc:
                print(f"[ClockWidget] Main-thread callback failed: {exc}")
        self.root.after(25, self._drain_ui_queue)

    def _schedule_settings_refresh(self, *args, recalculate_debt=False):
        """Debounce redraw/save work generated by settings interactions."""
        if not hasattr(self, 'canvas'):
            return
        self._settings_refresh_needs_debt |= recalculate_debt
        if self._settings_refresh_id is not None:
            self.root.after_cancel(self._settings_refresh_id)
        self._settings_refresh_id = self.root.after(150, self._flush_settings_refresh)

    def _flush_settings_refresh(self):
        self._settings_refresh_id = None
        recalculate_debt = self._settings_refresh_needs_debt
        self._settings_refresh_needs_debt = False
        self._save_sleep_settings()
        if recalculate_debt and self.raw_sleep_logs:
            self._recalculate_debt()
        else:
            self.update_metric_labels()
            self._schedule_clock_redraw()

    def _schedule_clock_redraw(self, layers=None):
        """Coalesce data-driven redraws and retain unaffected canvas layers."""
        if layers is None:
            self._full_clock_redraw_pending = True
            self._clock_dirty_layers.clear()
        elif not self._full_clock_redraw_pending:
            self._clock_dirty_layers.update(layers)
        if getattr(self, '_clock_redraw_id', None) is None:
            self._clock_redraw_id = self.root.after_idle(self._flush_clock_redraw)

    def _flush_clock_redraw(self):
        self._clock_redraw_id = None
        if self.root.winfo_exists():
            layers = None if self._full_clock_redraw_pending else set(self._clock_dirty_layers)
            self._full_clock_redraw_pending = False
            self._clock_dirty_layers.clear()
            if layers is None or layers:
                self.draw_clock(layers)

    def _retry_full_clock_draw(self):
        self._layout_retry_id = None
        self._schedule_clock_redraw()

    def _configure_settings_styles(self):
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(
            "Clock.TNotebook",
            background=self.UI_BG,
            borderwidth=0,
            tabmargins=(10, 8, 10, 0),
        )
        style.configure(
            "Clock.TNotebook.Tab",
            background=self.UI_SURFACE,
            foreground=self.UI_MUTED,
            borderwidth=0,
            padding=(15, 8),
            font=("Segoe UI", 9, "bold"),
        )
        style.map(
            "Clock.TNotebook.Tab",
            background=[("selected", self.UI_SURFACE_ALT), ("active", self.UI_SURFACE_ALT)],
            foreground=[("selected", self.UI_TEXT), ("active", self.UI_TEXT)],
        )

    def _settings_section(self, parent, title, description=None):
        card = tk.Frame(
            parent,
            bg=self.UI_SURFACE,
            highlightthickness=1,
            highlightbackground=self.UI_BORDER,
        )
        card.pack(fill=tk.X, pady=(0, 10))
        header = tk.Frame(card, bg=self.UI_SURFACE)
        header.pack(fill=tk.X, padx=12, pady=(10, 6 if description else 8))
        tk.Label(
            header,
            text=title,
            bg=self.UI_SURFACE,
            fg=self.UI_TEXT,
            font=("Segoe UI", 10, "bold"),
            anchor=tk.W,
        ).pack(fill=tk.X)
        if description:
            tk.Label(
                header,
                text=description,
                bg=self.UI_SURFACE,
                fg=self.UI_MUTED,
                font=("Segoe UI", 8),
                anchor=tk.W,
                justify=tk.LEFT,
                wraplength=370,
            ).pack(fill=tk.X, pady=(2, 0))
        body = tk.Frame(card, bg=self.UI_SURFACE)
        body.pack(fill=tk.X, padx=8, pady=(0, 10))
        return body

    def _settings_entry(self, parent, variable, width=7, validatecommand=None):
        entry = tk.Entry(
            parent,
            textvariable=variable,
            width=width,
            validate='key' if validatecommand else 'none',
            validatecommand=validatecommand or None,
            bg=self.UI_SURFACE_ALT,
            fg=self.UI_TEXT,
            insertbackground=self.UI_TEXT,
            disabledbackground=self.UI_SURFACE,
            disabledforeground=self.UI_MUTED,
            relief=tk.FLAT,
            highlightthickness=1,
            highlightbackground=self.UI_BORDER,
            highlightcolor=self.UI_ACCENT,
            font=("Segoe UI", 9),
            justify=tk.CENTER,
        )
        return entry

    def _settings_button(self, parent, text, command, accent=False, danger=False):
        fg = self.UI_DANGER if danger else self.UI_ACCENT if accent else self.UI_TEXT
        button = tk.Button(
            parent,
            text=text,
            command=command,
            bg=self.UI_SURFACE_ALT,
            fg=fg,
            activebackground=self.UI_BORDER,
            activeforeground=self.UI_TEXT,
            relief=tk.FLAT,
            bd=0,
            padx=12,
            pady=7,
            cursor="hand2",
            font=("Segoe UI", 9, "bold"),
        )
        return button

    def _new_settings_tab(self, notebook, title):
        tab = tk.Frame(notebook, bg=self.UI_BG, padx=12, pady=12)
        notebook.add(tab, text=title)
        return tab

    def _ensure_controls_window(self):
        if getattr(self, 'controls_window', None):
            return self.controls_window

        started_at = time.perf_counter()
        self._configure_settings_styles()
        win = self.controls_window = tk.Toplevel(self.root)
        win.title("Clock Settings")
        win.configure(bg=self.UI_BORDER)
        win.overrideredirect(True)
        win.withdraw()
        win.geometry("560x680")
        win.attributes('-topmost', self.always_on_top.get())

        outer = self.controls_frame = tk.Frame(win, bg=self.UI_BG, bd=0)
        outer.pack(fill=tk.BOTH, expand=True, padx=1, pady=1)

        title_bar = tk.Frame(outer, bg=self.UI_SURFACE, height=48)
        title_bar.pack(fill=tk.X)
        title_bar.pack_propagate(False)
        title_copy = tk.Frame(title_bar, bg=self.UI_SURFACE)
        title_copy.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(14, 0), pady=7)
        title_label = tk.Label(
            title_copy, text="Clock settings", bg=self.UI_SURFACE, fg=self.UI_TEXT,
            font=("Segoe UI", 11, "bold"), anchor=tk.W,
        )
        title_label.pack(fill=tk.X)
        subtitle_label = tk.Label(
            title_copy, text="Display, sleep, energy, and connected data",
            bg=self.UI_SURFACE, fg=self.UI_MUTED, font=("Segoe UI", 8), anchor=tk.W,
        )
        subtitle_label.pack(fill=tk.X)
        close_btn = tk.Button(
            title_bar, text="×", command=self.make_controls_hidden,
            bg=self.UI_SURFACE, fg=self.UI_MUTED,
            activebackground=self.UI_DANGER, activeforeground="white",
            relief=tk.FLAT, bd=0, padx=14, pady=5,
            cursor="hand2", font=("Segoe UI", 16),
        )
        close_btn.pack(side=tk.RIGHT, fill=tk.Y)
        close_btn.configure(text="X")
        settings_btn = tk.Button(
            title_bar, text=chr(0x2699), command=self.toggle_controls,
            bg=self.UI_SURFACE, fg=self.UI_MUTED,
            activebackground=self.UI_SURFACE_ALT, activeforeground=self.UI_ACCENT,
            relief=tk.FLAT, bd=0, padx=10, pady=5,
            cursor="hand2", font=("Segoe UI Symbol", 14),
        )
        settings_btn.pack(side=tk.RIGHT, fill=tk.Y)

        for widget in (title_bar, title_copy, title_label, subtitle_label):
            widget.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.controls_window))
            widget.bind("<B1-Motion>", self.on_drag_motion)
            widget.bind("<ButtonRelease-1>", self.on_drag_end)

        notebook = self.settings_notebook = ttk.Notebook(outer, style="Clock.TNotebook")
        notebook.pack(fill=tk.BOTH, expand=True)
        display_tab = self._new_settings_tab(notebook, "Display")
        sleep_tab = self._new_settings_tab(notebook, "Sleep")
        energy_tab = self._new_settings_tab(notebook, "Energy")
        integrations_tab = self._new_settings_tab(notebook, "Data")

        def on_visual_change():
            self._schedule_settings_refresh()

        def on_naps_change():
            self._schedule_settings_refresh(recalculate_debt=True)
            self.update_fitbit_data()

        def validate_num(value):
            if value in ("", "."):
                return True
            try:
                float(value)
                return True
            except ValueError:
                return False

        number_validation = (self.root.register(validate_num), '%P')

        appearance = self._settings_section(
            display_tab, "Clock face", "Choose the information drawn directly on the 24-hour dial."
        )
        self.numbers_toggle = self._add_toggle("Hour numbers", self.show_numbers, on_visual_change, appearance)
        self.sun_moon_toggle = self._add_toggle("Sun and moon markers", self.show_sun_moon, on_visual_change, appearance)
        self.calendar_toggle = self._add_toggle("Calendar events", self.show_calendar, on_visual_change, appearance)

        wake = self._settings_section(display_tab, "Wake marker")
        wake_row = tk.Frame(wake, bg=self.UI_SURFACE)
        wake_row.pack(fill=tk.X)
        self.manual_wake_toggle = self._add_toggle(
            "Show manual wake time", self.show_manual_wake, on_visual_change, wake_row
        )
        self.manual_wake_toggle.pack_configure(side=tk.LEFT, fill=tk.X, expand=True)
        self.manual_wake_entry = self._settings_entry(wake_row, self.manual_wake_time, width=8)
        self.manual_wake_entry.pack(side=tk.RIGHT, padx=(8, 6), pady=4)

        behavior = self._settings_section(
            display_tab, "Window behavior", "Tune how quickly the widget reveals and hides its controls."
        )
        self.top_toggle = self._add_toggle("Always on top", self.always_on_top, self.toggle_topmost, behavior)
        delay_row = tk.Frame(behavior, bg=self.UI_SURFACE)
        delay_row.pack(fill=tk.X, padx=6, pady=(5, 2))
        tk.Label(
            delay_row, text="Hover delay", bg=self.UI_SURFACE, fg=self.UI_MUTED,
            font=("Segoe UI", 9), anchor=tk.W,
        ).grid(row=0, column=0, sticky="w")
        self.hover_delay_entry = self._settings_entry(delay_row, self.hover_delay_var, validatecommand=number_validation)
        self.hover_delay_entry.grid(row=0, column=1, padx=(10, 18))
        tk.Label(
            delay_row, text="Hide delay", bg=self.UI_SURFACE, fg=self.UI_MUTED,
            font=("Segoe UI", 9), anchor=tk.W,
        ).grid(row=0, column=2, sticky="w")
        self.timeout_delay_entry = self._settings_entry(delay_row, self.timeout_delay_var, validatecommand=number_validation)
        self.timeout_delay_entry.grid(row=0, column=3, padx=(10, 0))

        metrics = self._settings_section(sleep_tab, "Current metrics")
        self.bathyphase_label = tk.Label(
            metrics, text="Bathyphase  ·  --:-- --", bg=self.UI_SURFACE, fg=self.UI_TEXT,
            font=("Segoe UI", 10, "bold"), anchor=tk.W, padx=6, pady=4,
        )
        self.bathyphase_label.pack(fill=tk.X)
        self.acrophase_label = tk.Label(
            metrics, text="Predicted acrophase  ·  --:-- --", bg=self.UI_SURFACE, fg=self.UI_TEXT,
            font=("Segoe UI", 10, "bold"), anchor=tk.W, padx=6, pady=4,
        )
        self.acrophase_label.pack(fill=tk.X)
        self.efficiency_label = tk.Label(
            metrics, text="Sleep efficiency  ·  --.-%", bg=self.UI_SURFACE, fg=self.UI_TEXT,
            font=("Segoe UI", 10, "bold"), anchor=tk.W, padx=6, pady=4,
        )
        self.efficiency_label.pack(fill=tk.X)

        sleep_display = self._settings_section(sleep_tab, "Sleep display")
        self.sleep_toggle = self._add_toggle("Sleep arc", self.show_sleep, on_visual_change, sleep_display)
        self.bedtime_toggle = self._add_toggle("Use time in bed", self.show_total_bedtime, on_visual_change, sleep_display)
        self.debt_text_toggle = self._add_toggle("Sleep debt label", self.show_sleep_debt_text, on_visual_change, sleep_display)
        self.bathyphase_toggle = self._add_toggle("Bathyphase marker", self.show_bathyphase, on_visual_change, sleep_display)
        self.acrophase_toggle = self._add_toggle("Acrophase marker", self.show_acrophase, on_visual_change, sleep_display)
        self.grogginess_toggle = self._add_toggle("Grogginess wedge", self.show_grogginess, on_visual_change, sleep_display)
        self.wind_down_toggle = self._add_toggle("Wind-down wedge", self.show_wind_down, on_visual_change, sleep_display)

        sleep_windows = self._settings_section(sleep_tab, "Related views")
        self.bedtime_countdown_toggle = self._add_toggle(
            "Bedtime countdown", self.show_bedtime_countdown, self.update_bedtime_countdown_visibility, sleep_windows
        )
        self.table_toggle = self._add_toggle(
            "Sleep log tab", self.show_sleep_table, self.update_sleep_table_visibility, sleep_windows
        )
        self.model_win_toggle = self._add_toggle(
            "Advanced model tab", self.show_sleep_model, self.update_sleep_model_visibility, sleep_windows
        )
        self.naps_toggle = self._add_toggle("Include naps", self.include_naps, on_naps_change, sleep_windows)

        energy_display = self._settings_section(
            energy_tab, "Energy curve", "Control how the predicted alertness curve is calculated and displayed."
        )
        self.energy_toggle = self._add_toggle("Show energy curve", self.show_energy, on_visual_change, energy_display)
        self.energy_pct_toggle = self._add_toggle("Show current percentage", self.show_energy_pct, on_visual_change, energy_display)
        self.normalize_toggle = self._add_toggle("Normalize daily range", self.normalize_energy, on_visual_change, energy_display)
        self.debt_toggle = self._add_toggle("Factor in sleep debt", self.show_sleep_debt, on_visual_change, energy_display)

        energy_actions = self._settings_section(energy_tab, "Refresh data")
        self.refresh_btn = self._settings_button(
            energy_actions, "Refresh Fitbit now", lambda: self.update_fitbit_data(force=True), accent=True
        )
        self.refresh_btn.pack(anchor=tk.W, padx=6, pady=4)

        fitbit = self._settings_section(
            integrations_tab, "Fitbit", "Sleep, heart-rate, and recovery data used by the energy model."
        )
        fitbit_btn = self._settings_button(
            fitbit, "Refresh Fitbit", lambda: self.update_fitbit_data(force=True), accent=True
        )
        fitbit_btn.pack(anchor=tk.W, padx=6, pady=4)
        calendar = self._settings_section(
            integrations_tab, "Google Calendar", "Events are rendered as colored arcs on the clock face."
        )
        self.calendar_refresh_btn = self._settings_button(calendar, "Refresh calendar", self.update_calendar_data)
        self.calendar_refresh_btn.pack(anchor=tk.W, padx=6, pady=4)

        # Sleep log and model are retained pages in this same native window.
        # Avoiding additional Toplevel mappings is considerably faster on Windows.
        self.sleep_table_page = tk.Frame(notebook, bg=self.UI_BG, padx=8, pady=8)
        self.sleep_model_page = tk.Frame(notebook, bg=self.UI_BG, padx=8, pady=8)
        self.sleep_table_window = self.sleep_table_page
        self.sleep_model_window = self.sleep_model_page
        self._sleep_table_built = False
        self._sleep_model_built = False
        if self.show_sleep_table.get():
            notebook.add(self.sleep_table_page, text="Sleep log")
        if self.show_sleep_model.get():
            notebook.add(self.sleep_model_page, text="Model")
        notebook.bind("<<NotebookTabChanged>>", self._on_settings_tab_changed, add="+")

        for widget in (win, outer, notebook):
            widget.bind("<Enter>", self.on_enter)
            widget.bind("<Leave>", self.on_leave)
        win.bind("<Configure>", self._cache_window_event, add="+")
        win.bind("<Map>", self._cache_window_event, add="+")
        win.bind("<Escape>", lambda event: self.make_controls_hidden())
        self.update_metric_labels()
        self.root.after(60, self._prewarm_settings_pages)
        self.root.after(120, self._prewarm_controls_window)
        self._log_timing("settings shell build", started_at)
        return win

    def _ensure_sleep_table_window(self):
        if not getattr(self, 'controls_window', None):
            self._ensure_controls_window()
        if getattr(self, '_sleep_table_built', False):
            return self.sleep_table_page
        started_at = time.perf_counter()
        self.sleep_table_window = self.sleep_table_page
        self._build_sleep_table()
        self._sleep_table_built = True
        self._log_timing("sleep log page build", started_at)
        return self.sleep_table_page

    def _ensure_sleep_model_window(self):
        if not getattr(self, 'controls_window', None):
            self._ensure_controls_window()
        if getattr(self, '_sleep_model_built', False):
            return self.sleep_model_page
        started_at = time.perf_counter()
        self.sleep_model_window = self.sleep_model_page
        self._build_sleep_model()
        self._sleep_model_built = True
        self._log_timing("advanced model page build", started_at)
        return self.sleep_model_page

    def _settings_tab_present(self, page):
        return bool(getattr(self, 'settings_notebook', None)) and str(page) in self.settings_notebook.tabs()

    def _settings_page_selected(self, page):
        if not self._settings_tab_present(page) or not self.is_controls_visible:
            return False
        return self.settings_notebook.select() == str(page)

    def _on_settings_tab_changed(self, _event=None):
        if getattr(self, 'sleep_table_page', None) and self._settings_page_selected(self.sleep_table_page):
            self._ensure_sleep_table_window()
            if self._sleep_table_needs_refresh and not self._sleep_table_refresh_scheduled:
                self._sleep_table_refresh_scheduled = True
                self.root.after_idle(self._refresh_sleep_table_if_visible)
        elif getattr(self, 'sleep_model_page', None) and self._settings_page_selected(self.sleep_model_page):
            self._ensure_sleep_model_window()

    def _prewarm_settings_pages(self):
        if self._closing or not getattr(self, 'controls_window', None):
            return
        self._ensure_sleep_table_window()
        self.root.after(40, self._ensure_sleep_model_window)

    def _prewarm_controls_window(self):
        """Realize the native settings HWND once without flashing it onscreen."""
        if (self._closing or self._settings_realized or self.is_controls_visible
                or not getattr(self, 'controls_window', None)):
            return
        self._settings_prewarm_pending = True
        try:
            self.controls_window.attributes('-alpha', 0.0)
        except tk.TclError:
            pass
        offscreen_x = self.root.winfo_screenwidth() + 200
        self.controls_window.geometry(f"560x680+{offscreen_x}+0")
        self.controls_window.deiconify()
        self.controls_window.update_idletasks()
        self.root.after_idle(self._finish_controls_prewarm)

    def _finish_controls_prewarm(self):
        if not getattr(self, 'controls_window', None):
            return
        self._settings_prewarm_pending = False
        self._settings_realized = True
        if not self.is_controls_visible:
            self.controls_window.withdraw()
        try:
            self.controls_window.attributes('-alpha', 1.0)
        except tk.TclError:
            pass

    def _ensure_bedtime_countdown_window(self):
        if getattr(self, 'bedtime_countdown_window', None):
            return self.bedtime_countdown_window
        win = self.bedtime_countdown_window = tk.Toplevel(self.root)
        win.title("Bedtime Countdown")
        win.configure(bg="#1a1a1a")
        win.overrideredirect(True)
        win.withdraw()
        win.attributes('-topmost', self.always_on_top.get())
        win.bind("<Enter>", self.on_enter)
        win.bind("<Leave>", self.on_leave)
        win.bind("<Configure>", self._cache_window_event, add="+")
        self._build_bedtime_countdown()
        return win

    def toggle_topmost(self):
        self.root.attributes('-topmost', self.always_on_top.get())
        if hasattr(self, 'controls_window'):
            self.controls_window.attributes('-topmost', self.always_on_top.get())
        if hasattr(self, 'bedtime_countdown_window'):
            self.bedtime_countdown_window.attributes('-topmost', self.always_on_top.get())

    def make_clock_solid(self):
        if not self.is_clock_transparent: return
        self.is_clock_transparent = False
        
        bg_color = self.solid_bg
        self.root.configure(bg=bg_color)
        self.main_frame.configure(bg=bg_color)
        self.canvas.configure(bg=bg_color)
        if hasattr(self, 'bedtime_countdown_window') and hasattr(self, '_bc_frame'):
            self.bedtime_countdown_window.configure(bg=bg_color)
            self._bc_frame.configure(bg=bg_color)
            self._bc_label.configure(bg=bg_color)
            self._bc_countdown.configure(bg=bg_color)
            self._bc_target.configure(bg=bg_color)

        # Keep the native window style fixed. Toggling decorations on every
        # hover forces Windows to rebuild the HWND and emits resize events.
        self._draw_hover_controls()
        if getattr(self, 'show_bedtime_countdown', None) and self.show_bedtime_countdown.get():
            self.update_bedtime_countdown_visibility(persist=False)

    def make_clock_transparent(self):
        self._clock_hide_timer_started = False
        if self.is_clock_transparent: return
        self.is_clock_transparent = True

        # The auxiliary countdown follows the same timeout as the clock.
        if hasattr(self, 'bedtime_countdown_window') and self.bedtime_countdown_window.winfo_viewable():
            self.bedtime_countdown_window.withdraw()
        self._stop_bedtime_countdown_timer()
        self._clear_hover_controls()
        
        bg_color = self.transparent_key
        self.root.configure(bg=bg_color)
        self.main_frame.configure(bg=bg_color)
        self.canvas.configure(bg=bg_color)
        if hasattr(self, 'bedtime_countdown_window') and hasattr(self, '_bc_frame'):
            self.bedtime_countdown_window.configure(bg=bg_color)
            self._bc_frame.configure(bg=bg_color)
            self._bc_label.configure(bg=bg_color)
            self._bc_countdown.configure(bg=bg_color)
            self._bc_target.configure(bg=bg_color)

    def toggle_controls(self):
        if self.is_controls_visible:
            self.make_controls_hidden(reason="toggle")
        else:
            self.make_clock_solid()
            self._request_hover_refresh()
            self.make_controls_visible()

    def _clear_hover_controls(self):
        """Remove the canvas-native settings/exit affordances."""
        if getattr(self, "canvas", None) is not None:
            self.canvas.delete(self._hover_controls_tag)

    def _on_hover_control_press(self, _event, action):
        """Run a canvas button action without starting a window drag."""
        self._drag_data = None
        self._resize_data = None
        self._canvas_control_gesture_token += 1
        gesture_token = self._canvas_control_gesture_token
        self._canvas_control_gesture = True
        action()
        self.root.after(1500, lambda token=gesture_token: self._expire_canvas_control_gesture(token))
        return "break"

    def _draw_hover_controls(self):
        """Draw compact settings and exit buttons over the solid clock face."""
        if getattr(self, "is_clock_transparent", True):
            self._clear_hover_controls()
            return
        try:
            w, h = self.canvas.winfo_width(), self.canvas.winfo_height()
        except tk.TclError:
            return
        if min(w, h) < 40:
            return
        center_x, center_y = w / 2, h / 2
        radius = min(w, h) / 2 - self.FACE_PADDING
        if radius < 20:
            return

        self._clear_hover_controls()
        button_r = max(9, min(13, radius * 0.14))
        # Anchor the pair to the upper-right of the whole borderless window.
        exit_x = w - button_r - 8
        settings_x = exit_x - button_r * 2.6
        settings_y = exit_y = max(button_r + 5, 10)
        tag = self._hover_controls_tag

        for x, y, item_tag, label in (
            (settings_x, settings_y, "settings_button", "Settings"),
            (exit_x, exit_y, "exit_button", "Exit"),
        ):
            self.canvas.create_oval(
                x - button_r, y - button_r, x + button_r, y + button_r,
                fill=self.UI_SURFACE, outline=self.UI_ACCENT, width=2,
                tags=(tag, item_tag),
            )
            self.canvas.tag_bind(
                item_tag, "<Enter>",
                lambda _event: self.canvas.configure(cursor="hand2"),
            )
            self.canvas.tag_bind(
                item_tag, "<Leave>",
                lambda _event: self.canvas.configure(cursor="fleur"),
            )
            self.canvas.tag_bind(
                item_tag, "<Button-1>",
                lambda event, action=(self.toggle_controls if item_tag == "settings_button" else self.on_close):
                    self._on_hover_control_press(event, action),
            )

        # Draw a real gear glyph plus a vector fallback. The fallback keeps
        # the control recognizable on systems whose symbol font is incomplete.
        for angle in range(0, 360, 45):
            rad = math.radians(angle)
            inner = button_r * 0.42
            outer = button_r * 0.82
            self.canvas.create_line(
                settings_x + inner * math.cos(rad), settings_y + inner * math.sin(rad),
                settings_x + outer * math.cos(rad), settings_y + outer * math.sin(rad),
                fill=self.UI_ACCENT, width=2, capstyle=tk.ROUND,
                tags=(tag, "settings_button"),
            )
        self.canvas.create_oval(
            settings_x - button_r * 0.34, settings_y - button_r * 0.34,
            settings_x + button_r * 0.34, settings_y + button_r * 0.34,
            fill=self.UI_ACCENT, outline="", tags=(tag, "settings_button"),
        )
        self.canvas.create_text(
            settings_x, settings_y, text=chr(0x2699), fill=self.UI_SURFACE,
            font=("Segoe UI Symbol", max(8, int(button_r * 0.9)), "bold"),
            tags=(tag, "settings_button"),
        )
        self.canvas.create_text(
            settings_x, settings_y - 0.5, text="⚙", fill=self.UI_SURFACE,
            font=("Segoe UI Symbol", max(8, int(button_r * 0.9)), "bold"),
            tags=(tag, "settings_button"),
        )
        # Overlay a deterministic cog shape so the control remains a cog even
        # when the platform's symbol font renders Unicode gear glyphs poorly.
        # Remove the earlier font/fallback pieces and redraw one canonical
        # button so no oversized spokes show beyond the requested cog radius.
        self.canvas.delete("settings_button")
        self.canvas.create_oval(
            settings_x - button_r, settings_y - button_r,
            settings_x + button_r, settings_y + button_r,
            fill=self.UI_SURFACE, outline=self.UI_ACCENT, width=2,
            tags=(tag, "settings_button"),
        )
        cog_points = []
        for index in range(32):
            angle = (2 * math.pi * index) / 32
            phase = index % 4
            tooth_radius = button_r * (0.36 if phase in (0, 3) else 0.50)
            cog_points.extend((
                settings_x + tooth_radius * math.cos(angle),
                settings_y + tooth_radius * math.sin(angle),
            ))
        self.canvas.create_polygon(
            cog_points, fill=self.UI_ACCENT, outline="",
            tags=(tag, "settings_button"),
        )
        self.canvas.create_oval(
            settings_x - button_r * 0.14, settings_y - button_r * 0.14,
            settings_x + button_r * 0.14, settings_y + button_r * 0.14,
            fill=self.UI_SURFACE, outline=self.UI_SURFACE,
            tags=(tag, "settings_button"),
        )
        self.canvas.tag_bind(
            "settings_button", "<Enter>",
            lambda _event: self.canvas.configure(cursor="hand2"),
        )
        self.canvas.tag_bind(
            "settings_button", "<Leave>",
            lambda _event: self.canvas.configure(cursor="fleur"),
        )
        self.canvas.tag_bind(
            "settings_button", "<Button-1>",
            lambda event: self._on_hover_control_press(event, self.toggle_controls),
        )
        cross = button_r * 0.42
        for x1, y1, x2, y2 in (
            (exit_x - cross, exit_y - cross, exit_x + cross, exit_y + cross),
            (exit_x - cross, exit_y + cross, exit_x + cross, exit_y - cross),
        ):
            self.canvas.create_line(
                x1, y1, x2, y2, fill=self.UI_DANGER, width=2, capstyle=tk.ROUND,
                tags=(tag, "exit_button"),
            )

    def make_controls_visible(self, select_page=None):
        if getattr(self, 'is_controls_visible', False):
            return
        started_at = time.perf_counter()
        self._cancel_controls_hide_timer()
        self._ensure_controls_window()
        if self._settings_prewarm_pending:
            self._finish_controls_prewarm()
        self.is_controls_visible = True
        
        if not getattr(self, '_controls_detached', False):
            rect = self._window_rects.get("root")
            if rect is None:
                self._refresh_window_rect("root", self.root)
                rect = self._window_rects.get("root")
            rx, ry, rw, _ = rect or (0, 0, self.root.winfo_width(), 0)
            self.controls_window.geometry(f"+{rx + rw + 10}+{ry}")
            
        self.controls_window.deiconify()
        self.controls_window.lift()
        self.controls_window.update_idletasks()
        # Windows may report the prewarmed/off-screen rectangle briefly after
        # mapping. Keep the click latched while the HWND settles, then sample
        # its real bounds a few times without requiring a drag event.
        self._controls_open_grace_until = time.monotonic() + 0.75
        self._settings_realized = True
        self._refresh_window_rect("controls", self.controls_window)
        self.root.after_idle(self._refresh_controls_rect_if_visible)
        self.root.after(40, self._refresh_controls_rect_if_visible)
        self.root.after(140, self._refresh_controls_rect_if_visible)
        if select_page and self._settings_tab_present(select_page):
            self.settings_notebook.select(select_page)
        self._on_settings_tab_changed()
        self._draw_hover_controls()
        self._controls_open_count += 1
        open_kind = "first settings open" if self._controls_open_count == 1 else "repeat settings open"
        self.root.after_idle(lambda: self._log_timing(open_kind, started_at))

    def make_controls_hidden(self, reason="explicit"):
        self._controls_hide_timer_started = False
        self._controls_open_grace_until = 0.0
        if not hasattr(self, 'controls_window'):
            return
        if not getattr(self, 'is_controls_visible', False):
            return
        self.is_controls_visible = False
        self.controls_window.withdraw()
        self._window_rects.pop("controls", None)
        if not self.is_clock_transparent:
            self._draw_hover_controls()

    def update_sleep_table_visibility(self):
        """Add or remove the retained sleep-log settings tab."""
        self._ensure_controls_window()
        if self.show_sleep_table.get():
            self._ensure_sleep_table_window()
            if not self._settings_tab_present(self.sleep_table_page):
                self.settings_notebook.add(self.sleep_table_page, text="Sleep log")
            if self.is_controls_visible:
                self.settings_notebook.select(self.sleep_table_page)
                self._on_settings_tab_changed()
        else:
            if self._settings_tab_present(self.sleep_table_page):
                self.settings_notebook.hide(self.sleep_table_page)
        self._save_sleep_settings()

    def _refresh_sleep_table_if_visible(self):
        self._sleep_table_refresh_scheduled = False
        if not self.show_sleep_table.get() or not getattr(self, 'is_controls_visible', False):
            return
        if not getattr(self, 'sleep_table_page', None) or not self._settings_page_selected(self.sleep_table_page):
            return
        self.populate_sleep_table()

    def update_sleep_model_visibility(self):
        """Add or remove the retained advanced-model settings tab."""
        self._ensure_controls_window()
        if self.show_sleep_model.get():
            self._ensure_sleep_model_window()
            if not self._settings_tab_present(self.sleep_model_page):
                self.settings_notebook.add(self.sleep_model_page, text="Model")
            if self.is_controls_visible:
                self.settings_notebook.select(self.sleep_model_page)
        else:
            if self._settings_tab_present(self.sleep_model_page):
                self.settings_notebook.hide(self.sleep_model_page)
        self._save_sleep_settings()

    # ── Bedtime Countdown Window ─────────────────────────────────────────

    def _build_bedtime_countdown(self):
        """Build the standalone bedtime countdown window UI."""
        win = self.bedtime_countdown_window
        win.attributes("-transparentcolor", self.transparent_key)
        initial_bg = self.transparent_key if getattr(self, 'is_clock_transparent', False) else self.solid_bg
        win.configure(bg=initial_bg)

        frame = tk.Frame(win, bg=initial_bg)
        self._bc_frame = frame
        frame.pack(fill=tk.BOTH, expand=True, padx=12, pady=8)

        self._bc_label = tk.Label(
            frame, text="Bedtime", bg=initial_bg, fg="white",
            font=("Segoe UI", 11), anchor=tk.W
        )
        self._bc_label.pack(fill=tk.X)

        self._bc_countdown = tk.Label(
            frame, text="--:--:--", bg=initial_bg, fg="white",
            font=("Segoe UI", 22, "bold"), anchor=tk.W
        )
        self._bc_countdown.pack(fill=tk.X)

        self._bc_target = tk.Label(
            frame, text="", bg=initial_bg, fg="#cccccc",
            font=("Segoe UI", 12), anchor=tk.W
        )
        self._bc_target.pack(fill=tk.X)

        # Drag and hover bindings across window, frame, and labels
        for w in (win, frame, self._bc_label, self._bc_countdown, self._bc_target):
            w.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.bedtime_countdown_window))
            w.bind("<B1-Motion>", lambda e: self.on_drag_motion(e))
            w.bind("<ButtonRelease-1>", self.on_drag_end)
            w.bind("<Enter>", self.on_enter)
            w.bind("<Leave>", self.on_leave)

        self._bc_timer_id = None

    def _resolve_bedtime_target(self):
        """Calculate the next bedtime target as a datetime, mirroring Android's BedtimeNotificationManager.

        Uses the latest main sleep start minus 90 minutes, rounded to nearest 5 min,
        clamped to 10:00 PM when fall-asleep is in [22:00, 23:30].
        Falls back to 10:00 PM if no main sleep data is available.
        """
        LEAD_MINUTES = 90
        DEFAULT_HOUR, DEFAULT_MINUTE = 22, 0
        now = datetime.datetime.now()

        main_sleep = None
        if self.raw_sleep_logs and self.active_sleep_date:
            main_sleep = next(
                (log for log in self.raw_sleep_logs
                 if log.get('dateOfSleep') == self.active_sleep_date
                 and log.get('isMainSleep', False)),
                None
            )

        bedtime_time = None
        if main_sleep:
            try:
                start_dt = datetime.datetime.fromisoformat(
                    main_sleep['startTime'].replace('Z', '')
                )
                bedtime_dt = start_dt - datetime.timedelta(minutes=LEAD_MINUTES)
                # Round to nearest 5 minutes
                minute = bedtime_dt.minute
                rounded = ((minute + 2) // 5) * 5
                bedtime_dt = bedtime_dt.replace(minute=0, second=0, microsecond=0) + datetime.timedelta(minutes=rounded)

                # Clamp to 10:00 PM when fall-asleep is in [22:00, 23:30]
                fall_asleep_hm = start_dt.hour * 60 + start_dt.minute
                if 22 * 60 <= fall_asleep_hm <= 23 * 60 + 30:
                    bedtime_dt = bedtime_dt.replace(hour=22, minute=0, second=0, microsecond=0)

                bedtime_time = bedtime_dt.time()
            except (KeyError, ValueError):
                pass

        if bedtime_time is None:
            bedtime_time = datetime.time(DEFAULT_HOUR, DEFAULT_MINUTE)

        # Build next occurrence of this time-of-day
        target = now.replace(
            hour=bedtime_time.hour, minute=bedtime_time.minute,
            second=0, microsecond=0
        )
        if target <= now:
            target += datetime.timedelta(days=1)

        return target

    def update_bedtime_countdown_visibility(self, *args, persist=True):
        """Show/hide the standalone bedtime countdown window and start/stop its timer."""
        if persist:
            self._save_sleep_settings()

        if self.show_bedtime_countdown.get() and not self.is_clock_transparent:
            self._ensure_bedtime_countdown_window()
            bg_color = self.transparent_key if getattr(self, 'is_clock_transparent', False) else self.solid_bg
            self.bedtime_countdown_window.configure(bg=bg_color)
            if hasattr(self, '_bc_frame'): self._bc_frame.configure(bg=bg_color)
            if hasattr(self, '_bc_label'): self._bc_label.configure(bg=bg_color)
            if hasattr(self, '_bc_countdown'): self._bc_countdown.configure(bg=bg_color)
            if hasattr(self, '_bc_target'): self._bc_target.configure(bg=bg_color)

            if not self.bedtime_countdown_window.winfo_viewable():
                # Position below the clock widget on first show
                rx = self.root.winfo_rootx()
                ry = self.root.winfo_rooty()
                rh = self.root.winfo_height()
                self.bedtime_countdown_window.geometry(f"+{rx}+{ry + rh + 10}")
            self.bedtime_countdown_window.deiconify()
            self.bedtime_countdown_window.lift()
            self._refresh_window_rect("countdown", self.bedtime_countdown_window)
            self._start_bedtime_countdown_timer()
        else:
            self._stop_bedtime_countdown_timer()
            if hasattr(self, 'bedtime_countdown_window'):
                self.bedtime_countdown_window.withdraw()
            self._window_rects.pop("countdown", None)

    def _start_bedtime_countdown_timer(self):
        """Start exactly one visible countdown timer chain."""
        if getattr(self, '_bc_timer_id', None) is None:
            self._update_bedtime_countdown_tick()

    def _stop_bedtime_countdown_timer(self):
        timer_id = getattr(self, '_bc_timer_id', None)
        if timer_id is not None:
            try:
                self.root.after_cancel(timer_id)
            except tk.TclError:
                pass
        self._bc_timer_id = None

    def _update_bedtime_countdown_tick(self):
        """Tick the bedtime countdown display every second."""
        self._bc_timer_id = None
        if (not self.show_bedtime_countdown.get() or self.is_clock_transparent
                or not getattr(self, 'bedtime_countdown_window', None)
                or not self.bedtime_countdown_window.winfo_viewable()):
            return

        now = datetime.datetime.now()
        target = self._resolve_bedtime_target()

        # Overdue check: if bedtime just passed within last 6 hours, show overdue
        last_bedtime = target - datetime.timedelta(days=1)
        is_overdue = (now >= last_bedtime and
                      now < last_bedtime + datetime.timedelta(hours=6))

        remaining = target - now
        total_seconds = max(0, int(remaining.total_seconds()))
        minutes_remaining = total_seconds / 60.0

        # Color: interpolate white → gray (#888) over last 90 minutes
        if is_overdue:
            color = "#888888"
        elif minutes_remaining > 90:
            color = "#ffffff"
        else:
            progress = max(0.0, min(1.0, (90.0 - minutes_remaining) / 90.0))
            channel = int(255 - (255 - 136) * progress)
            color = f"#{channel:02x}{channel:02x}{channel:02x}"

        if is_overdue:
            countdown_str = "GO TO BED!"
            target_str = ""
        else:
            hours = total_seconds // 3600
            minutes = (total_seconds % 3600) // 60
            seconds = total_seconds % 60
            countdown_str = f"{hours}:{minutes:02d}:{seconds:02d}"
            target_str = target.strftime("%I:%M %p").lstrip("0")

        self._bc_label.configure(fg=color)
        self._bc_countdown.configure(fg=color, text=countdown_str)
        self._bc_target.configure(fg=color, text=target_str)

        self._bc_timer_id = self.root.after(1000, self._update_bedtime_countdown_tick)

    def make_solid(self):
        self.make_clock_solid()

        # Data freshness remains hover-driven, while the full settings window is
        # opened deliberately by clicking the clock or using the tray menu.
        self._request_hover_refresh()

        if not getattr(self, '_checking_nearby', False):
            self._checking_nearby = True
            self.check_nearby()

    def _request_hover_refresh(self):
        """Request stale integrations without coupling them to menu visibility."""
        now_monotonic = time.monotonic()
        if now_monotonic - self._last_hover_refresh_check < 1.0:
            return
        self._last_hover_refresh_check = now_monotonic
        now = datetime.datetime.now()

        last_fitbit = getattr(self, 'last_fitbit_update', None)
        if last_fitbit is None or (now - last_fitbit).total_seconds() > 300:
            print("[ClockWidget] Opportunistic hover refresh: Fitbit.")
            self.update_fitbit_data(reason="hover")

        last_cal = getattr(self, '_last_calendar_refresh_time', None)
        if last_cal is None or (now - last_cal).total_seconds() > 300:
            print("[ClockWidget] Opportunistic hover refresh: Calendar.")
            self.update_calendar_data(reason="hover")

    def make_transparent(self):
        self._checking_nearby = False
        if getattr(self, '_clock_hide_timer_started', False):
            self.root.after_cancel(self._clock_hide_timer_id)
            self._clock_hide_timer_started = False
        if getattr(self, '_controls_hide_timer_started', False):
            self.root.after_cancel(self._controls_hide_timer_id)
            self._controls_hide_timer_started = False
        self.make_clock_transparent()
        self.make_controls_hidden(reason="make_transparent")

    def _cache_window_event(self, event):
        # Drag frames update the cached rectangles from the geometry being
        # applied. Avoid four synchronous winfo calls for every Configure event.
        if getattr(self, '_drag_in_progress', False):
            return
        widget = event.widget
        if widget == self.root:
            self._refresh_window_rect("root", self.root)
        elif widget == getattr(self, 'controls_window', None) and self.is_controls_visible:
            self._refresh_window_rect("controls", widget)
        elif widget == getattr(self, 'bedtime_countdown_window', None):
            self._refresh_window_rect("countdown", widget)

    def _refresh_window_rect(self, name, window):
        try:
            self._window_rects[name] = (
                window.winfo_rootx(), window.winfo_rooty(),
                window.winfo_width(), window.winfo_height(),
            )
        except tk.TclError:
            self._window_rects.pop(name, None)

    def _point_in_window(self, name, x, y, margin=0):
        rect = self._window_rects.get(name)
        if not rect:
            return False
        wx, wy, width, height = rect
        return (wx - margin <= x <= wx + width + margin
                and wy - margin <= y <= wy + height + margin)

    def _pointer_toplevel(self, x, y):
        """Return the actual Tk toplevel under the pointer, if any."""
        try:
            widget = self.root.winfo_containing(x, y)
            return widget.winfo_toplevel() if widget is not None else None
        except tk.TclError:
            return None

    def _refresh_controls_rect_if_visible(self):
        if not self.is_controls_visible or not getattr(self, 'controls_window', None):
            return
        try:
            self.controls_window.update_idletasks()
            self._refresh_window_rect("controls", self.controls_window)
        except tk.TclError:
            pass

    def _cancel_hover_timer(self):
        if getattr(self, '_hover_timer_started', False):
            self.root.after_cancel(self._hover_timer_id)
            self._hover_timer_started = False

    def _cancel_clock_hide_timer(self):
        if getattr(self, '_clock_hide_timer_started', False):
            self.root.after_cancel(self._clock_hide_timer_id)
            self._clock_hide_timer_started = False

    def _cancel_controls_hide_timer(self):
        if getattr(self, '_controls_hide_timer_started', False):
            self.root.after_cancel(self._controls_hide_timer_id)
            self._controls_hide_timer_started = False

    def check_nearby(self):
        if not getattr(self, '_checking_nearby', False):
            return
            
        # If left mouse button is pressed (resizing or dragging), don't change states
        try:
            import ctypes
            if ctypes.windll.user32.GetKeyState(0x01) & 0x8000:
                self.root.after(250, self.check_nearby)
                return
        except:
            pass

        mx = self.root.winfo_pointerx()
        my = self.root.winfo_pointery()
        pointer_top = self._pointer_toplevel(mx, my)
        if pointer_top is not None:
            # Direct hit-testing does not depend on a Configure/drag event.
            in_root = pointer_top == self.root
            in_controls = (
                self.is_controls_visible
                and pointer_top == getattr(self, 'controls_window', None)
            )
            in_bedtime_timer = pointer_top == getattr(self, 'bedtime_countdown_window', None)
        else:
            # Transparent pixels can return no Tk window on Windows; cached
            # bounds remain a useful fallback around those edges.
            in_root = self._point_in_window("root", mx, my, margin=20)
            in_bedtime_timer = self._point_in_window("countdown", mx, my, margin=10)
            in_controls = self.is_controls_visible and self._point_in_window("controls", mx, my, margin=10)
        in_clock_area = in_root or in_bedtime_timer
        controls_mapping = (
            self.is_controls_visible
            and time.monotonic() < self._controls_open_grace_until
        )
        interaction_active = in_clock_area or in_controls or controls_mapping

        def get_hover_ms():
            try:
                return int(float(self.hover_delay_var.get()) * 1000)
            except:
                return 750

        def get_timeout_ms():
            try:
                return int(float(self.timeout_delay_var.get()) * 1000)
            except:
                return 1000

        if not interaction_active:
            self._cancel_hover_timer()
            if not getattr(self, '_clock_hide_timer_started', False):
                self._clock_hide_timer_started = True
                self._clock_hide_timer_id = self.root.after(get_timeout_ms(), self.make_clock_transparent)
        else:
            self._cancel_clock_hide_timer()
            if self.is_clock_transparent and in_clock_area:
                if not getattr(self, '_hover_timer_started', False):
                    self._hover_timer_started = True
                    self._hover_timer_id = self.root.after(get_hover_ms(), self._on_hover_timeout)
            else:
                self._cancel_hover_timer()
                if in_controls:
                    self.make_clock_solid()

        # Keep the settings window while the pointer is over either window;
        # once the pointer leaves both, restore the idle transparent state.
        if self.is_controls_visible and not interaction_active:
            if not getattr(self, '_controls_hide_timer_started', False):
                self._controls_hide_timer_started = True
                self._controls_hide_timer_id = self.root.after(
                    get_timeout_ms(),
                    lambda: self.make_controls_hidden(reason="pointer_timeout"),
                )
        else:
            self._cancel_controls_hide_timer()

        # Always continue polling mouse coordinates while windows are active
        self.root.after(250, self.check_nearby)

    def _on_hover_timeout(self):
        self._hover_timer_started = False
        self.make_solid()

    def on_enter(self, event):
        if not getattr(self, '_checking_nearby', False):
            self._checking_nearby = True
            self.check_nearby()

    def _load_sleep_settings(self):
        """Load settings from JSON."""
        today_str = datetime.datetime.now().strftime("%Y-%m-%d")
        self.explicit_dates = []
        if os.path.exists(self.sleep_settings_file):
            try:
                with open(self.sleep_settings_file, 'r') as f:
                    data = json.load(f)
                    self.explicit_dates = data.get("explicit_dates", [])
                    
                    # Load model parameters if they exist
                    if "model_params" in data:
                        p = data["model_params"]
                        self.bedtime_goal.set(p.get("bedtime_goal", 9.75))
                        self.tau_wake.set(p.get("tau_wake", 18.2))
                        self.tau_sleep.set(p.get("tau_sleep", 4.2))
                        self.tau_inertia.set(p.get("tau_inertia", 1.5))
                        self.debt_factor.set(p.get("debt_factor", 1.0))
                        self.circadian_offset.set(p.get("circadian_offset", 12.0))
                        self.use_bathyphase.set(p.get("use_bathyphase", True))
                        self.manual_wake_time.set(p.get("manual_wake_time", "9:00"))
                        self.show_manual_wake.set(p.get("show_manual_wake", True))
                        self.show_bathyphase.set(p.get("show_bathyphase", True))
                        self.show_acrophase.set(p.get("show_acrophase", True))
                        self.show_grogginess.set(p.get("show_grogginess", True))
                        self.show_wind_down.set(p.get("show_wind_down", True))
                        self.show_bedtime_countdown.set(p.get("show_bedtime_countdown", True))
                        self.show_sleep_table.set(p.get("show_sleep_table", True))
                        self.show_sleep_model.set(p.get("show_sleep_model", False))

                    return data.get("excluded_dates", [today_str])
            except:
                pass
        return [today_str]

    def _sync_sleep_inclusion(self, raw_logs: list | None = None) -> None:
        """Normalize excluded_dates: past nights included; today needs main sleep."""
        from energy_logic import sync_sleep_inclusion

        today_str = datetime.datetime.now().strftime("%Y-%m-%d")
        before = list(self.excluded_dates)
        self.excluded_dates = sync_sleep_inclusion(
            self.excluded_dates,
            getattr(self, "explicit_dates", []),
            raw_logs or getattr(self, "raw_sleep_logs", []),
            today_str,
        )
        if self.excluded_dates != before:
            self._save_sleep_settings()

    def _load_fitbit_config(self):
        config_path = os.path.join(os.path.dirname(__file__), "fitbit_config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, 'r') as f:
                    config = json.load(f)
                    self.FITBIT_CLIENT_ID = config.get("FITBIT_CLIENT_ID")
                    self.FITBIT_CLIENT_SECRET = config.get("FITBIT_CLIENT_SECRET")
                    print("[Fitbit] Config loaded from fitbit_config.json")
            except Exception as e:
                print(f"[Fitbit] Error loading config: {e}")
        
        if not self.FITBIT_CLIENT_ID or not self.FITBIT_CLIENT_SECRET:
            print("[Fitbit] WARNING: Credentials missing. Please create fitbit_config.json")

    def _save_sleep_settings(self):
        """Save settings to JSON."""
        try:
            data = {
                "excluded_dates": self.excluded_dates,
                "explicit_dates": getattr(self, 'explicit_dates', []),
                "model_params": {
                    "bedtime_goal": self.bedtime_goal.get(),
                    "tau_wake": self.tau_wake.get(),
                    "tau_sleep": self.tau_sleep.get(),
                    "tau_inertia": self.tau_inertia.get(),
                    "debt_factor": self.debt_factor.get(),
                    "circadian_offset": self.circadian_offset.get(),
                    "use_bathyphase": self.use_bathyphase.get(),
                    "manual_wake_time": self.manual_wake_time.get(),
                    "show_manual_wake": self.show_manual_wake.get(),
                    "show_bathyphase": self.show_bathyphase.get(),
                    "show_acrophase": self.show_acrophase.get(),
                    "show_grogginess": self.show_grogginess.get(),
                    "show_wind_down": self.show_wind_down.get(),
                    "show_bedtime_countdown": self.show_bedtime_countdown.get(),
                    "show_sleep_table": self.show_sleep_table.get(),
                    "show_sleep_model": self.show_sleep_model.get(),
                }
            }
            temp_path = f"{self.sleep_settings_file}.tmp"
            with open(temp_path, 'w') as f:
                json.dump(data, f)
            os.replace(temp_path, self.sleep_settings_file)
        except:
            pass

    def _window_cache_name(self, window):
        if window == self.root:
            return "root"
        if window == getattr(self, 'controls_window', None):
            return "controls"
        if window == getattr(self, 'bedtime_countdown_window', None):
            return "countdown"
        return None

    def _queue_drag_geometry(
            self, window, x, y, width, height, *, resize=False,
            follow_controls=False, controls_size=None):
        """Keep only the latest requested drag geometry for the next frame."""
        self._pending_drag_geometry = {
            "window": window,
            "name": self._window_cache_name(window),
            "x": int(x),
            "y": int(y),
            "width": int(width),
            "height": int(height),
            "resize": bool(resize),
            "follow_controls": bool(follow_controls),
            "controls_size": controls_size,
        }
        if self._drag_flush_id is None:
            self._drag_flush_id = self.root.after(
                self.DRAG_FRAME_MS, self._flush_pending_drag_geometry
            )

    def _apply_pending_drag_geometry(self):
        pending = self._pending_drag_geometry
        self._pending_drag_geometry = None
        if not pending:
            return

        window = pending["window"]
        x, y = pending["x"], pending["y"]
        width, height = pending["width"], pending["height"]
        try:
            if pending["resize"]:
                window.geometry(f"{width}x{height}+{x}+{y}")
            else:
                window.geometry(f"+{x}+{y}")
        except tk.TclError:
            return

        cache_name = pending["name"]
        if cache_name:
            self._window_rects[cache_name] = (x, y, width, height)

        if (pending["follow_controls"] and self.is_controls_visible
                and getattr(self, 'controls_window', None)):
            controls_x = x + width + 10
            controls_y = y
            try:
                self.controls_window.geometry(f"+{controls_x}+{controls_y}")
                controls_size = pending["controls_size"] or (560, 680)
                self._window_rects["controls"] = (
                    controls_x, controls_y,
                    int(controls_size[0]), int(controls_size[1]),
                )
            except tk.TclError:
                pass

    def _flush_pending_drag_geometry(self):
        self._drag_flush_id = None
        self._apply_pending_drag_geometry()

    def _flush_pending_drag_geometry_now(self):
        if self._drag_flush_id is not None:
            try:
                self.root.after_cancel(self._drag_flush_id)
            except tk.TclError:
                pass
            self._drag_flush_id = None
        self._apply_pending_drag_geometry()

    def _finalize_drag_rects(self):
        self._refresh_window_rect("root", self.root)
        if self.is_controls_visible and getattr(self, 'controls_window', None):
            self._refresh_window_rect("controls", self.controls_window)
        if getattr(self, 'bedtime_countdown_window', None):
            self._refresh_window_rect("countdown", self.bedtime_countdown_window)

    def on_drag_start(self, event, target_window=None):
        if target_window is None:
            target_window = self.root

        if self._canvas_control_gesture:
            self._drag_data = None
            self._resize_data = None
            return

        # Canvas-native settings/exit buttons own their click; do not let the
        # canvas drag binding toggle the window a second time on mouse-up.
        if target_window == self.root and event.widget == self.canvas:
            current = self.canvas.find_withtag("current")
            if current and self._hover_controls_tag in self.canvas.gettags(current[0]):
                self._drag_data = None
                self._resize_data = None
                return
            
        # Don't start a window drag if clicking on interactive widgets
        if isinstance(event.widget, (tk.Scale, tk.Checkbutton, tk.Button, tk.Entry)):
            return
            
        if target_window == self.root and getattr(self, 'is_clock_transparent', False):
            self._drag_data = None
            self._resize_data = None
            return
           
        w = target_window.winfo_width()
        h = target_window.winfo_height()
       
        rx = event.x_root - target_window.winfo_rootx()
        ry = event.y_root - target_window.winfo_rooty()
       
        if target_window == self.root:
            margin = 14
            left = rx <= margin
            right = rx >= w - margin
            top = ry <= margin
            bottom = ry >= h - margin
            if left or right or top or bottom:
                self._drag_data = None
                self._drag_in_progress = True
                self._resize_data = {
                    'x': event.x_root, 'y': event.y_root,
                    'wx': target_window.winfo_x(), 'wy': target_window.winfo_y(),
                    'width': w, 'height': h,
                    'left': left, 'right': right, 'top': top, 'bottom': bottom,
                }
                return
        elif target_window == getattr(self, 'controls_window', None):
            self._controls_detached = True

        follow_controls = False
        controls_size = None
        if (
                target_window == self.root
                and self.is_controls_visible
                and getattr(self, 'controls_window', None)
                and not getattr(self, '_controls_detached', False)):
            try:
                follow_controls = bool(self.controls_window.winfo_viewable())
                if follow_controls:
                    controls_rect = self._window_rects.get("controls")
                    if controls_rect:
                        controls_size = (controls_rect[2], controls_rect[3])
                    else:
                        controls_size = (
                            self.controls_window.winfo_width(),
                            self.controls_window.winfo_height(),
                        )
            except tk.TclError:
                follow_controls = False

        self._resize_data = None
        self._drag_in_progress = True
        self._drag_data = {'x': event.x_root, 'y': event.y_root,
                           'wx': target_window.winfo_x(), 'wy': target_window.winfo_y(),
                           'width': w, 'height': h,
                           'window': target_window, 'moved': False,
                           'follow_controls': follow_controls,
                           'controls_size': controls_size}

    def on_drag_motion(self, event):
        resize = getattr(self, '_resize_data', None)
        if resize:
            dx = event.x_root - resize['x']
            dy = event.y_root - resize['y']
            new_x, new_y = resize['wx'], resize['wy']
            new_w, new_h = resize['width'], resize['height']
            if resize['left']:
                new_x += dx
                new_w -= dx
            elif resize['right']:
                new_w += dx
            if resize['top']:
                new_y += dy
                new_h -= dy
            elif resize['bottom']:
                new_h += dy
            min_w, min_h = 120, 150
            if new_w < min_w:
                if resize['left']:
                    new_x -= min_w - new_w
                new_w = min_w
            if new_h < min_h:
                if resize['top']:
                    new_y -= min_h - new_h
                new_h = min_h
            self._queue_drag_geometry(
                self.root, new_x, new_y, new_w, new_h, resize=True
            )
            return

        drag = getattr(self, '_drag_data', None)
        if drag:
            target_window = drag['window']
            dx = event.x_root - drag['x']
            dy = event.y_root - drag['y']
            if abs(dx) > 3 or abs(dy) > 3:
                drag['moved'] = True
            new_x = drag['wx'] + dx
            new_y = drag['wy'] + dy
            self._queue_drag_geometry(
                target_window,
                new_x,
                new_y,
                drag['width'],
                drag['height'],
                follow_controls=drag['follow_controls'],
                controls_size=drag['controls_size'],
            )

    def on_drag_end(self, _event=None):
        # Apply the newest pointer position even when release happens before
        # the next scheduled drag frame.
        self._flush_pending_drag_geometry_now()
        resize = getattr(self, '_resize_data', None)
        drag = getattr(self, '_drag_data', None)
        control_gesture = self._canvas_control_gesture
        self._resize_data = None
        self._drag_data = None
        self._drag_in_progress = False
        self._canvas_control_gesture = False
        if resize or drag:
            self.root.after_idle(self._finalize_drag_rects)
        if control_gesture:
            return
        if resize:
            if self._resize_redraw_id is not None:
                self.root.after_cancel(self._resize_redraw_id)
                self._resize_redraw_id = None
            self._schedule_clock_redraw()
            return
        if drag and drag.get('window') == self.root and not drag.get('moved'):
            self.toggle_controls()

    def on_leave(self, event):
        if self.hover_timer is not None:
            self.root.after_cancel(self.hover_timer)
            self.hover_timer = None

    def _energy_display_pct(self, current_e: float) -> int:
        """Absolute energy percentage represented by the current curve value."""
        return min(100, max(0, int(round(current_e * 100))))

    def on_right_click_start(self, event):
        self._right_click_active = True
        self._update_phantom_hand(event.x_root, event.y_root)

    def on_right_click_motion(self, event):
        if not getattr(self, '_right_click_active', False):
            return
        now_ms = int(datetime.datetime.now().timestamp() * 1000)
        last_ms = getattr(self, '_last_phantom_update', 0)
        if now_ms - last_ms < 30:
            return
        self._last_phantom_update = now_ms
        self._update_phantom_hand(event.x_root, event.y_root)

    def on_right_click_end(self, event):
        self._right_click_active = False
        self.canvas.delete("phantom_hand")

    def _update_phantom_hand(self, x_root, y_root):
        w = self.canvas.winfo_width()
        h = self.canvas.winfo_height()
        cx = self.canvas.winfo_rootx() + w / 2
        cy = self.canvas.winfo_rooty() + h / 2
        dx = x_root - cx
        dy = y_root - cy
        
        radius = min(w, h) / 2 - self.FACE_PADDING
        if radius < 20: return
        
        rad = math.atan2(-dy, dx)
        phantom_hour = (18 - math.degrees(rad) / 15) % 24
        
        center_x, center_y = w / 2, h / 2
        
        current_r = radius # Fallback to perimeter
        current_e = 0.5    # Fallback energy
        
        if self.wake_hour is not None:
            self._apply_energy_curve_params()
            current_e = self.energy_curve.get_cached_energy(phantom_hour)
            current_r = self.energy_curve.get_display_radius(current_e, radius)

        hx = center_x + current_r * math.cos(rad)
        hy = center_y - current_r * math.sin(rad)
        
        arrow_len = radius * 0.12
        line_width = max(2, int(radius/25))
        if not self.canvas.find_withtag("phantom_hand_line"):
            self.canvas.create_line(
                center_x, center_y, hx, hy,
                fill="#222222", width=line_width,
                dash=(4, 4),
                arrow=tk.LAST, arrowshape=(arrow_len, arrow_len, arrow_len/3),
                capstyle=tk.ROUND,
                tags=("phantom_hand", "phantom_hand_line")
            )
            # Add center dot for phantom hand
            dot_r = line_width / 2
            self.canvas.create_oval(
                center_x - dot_r, center_y - dot_r,
                center_x + dot_r, center_y + dot_r,
                fill="#222222", outline="",
                tags=("phantom_hand", "phantom_hand_dot")
            )
        else:
            self.canvas.coords("phantom_hand_line", center_x, center_y, hx, hy)
            self.canvas.itemconfig("phantom_hand_line", width=line_width, arrowshape=(arrow_len, arrow_len, arrow_len/3), capstyle=tk.ROUND)
            dot_r = line_width / 2
            self.canvas.coords("phantom_hand_dot", center_x - dot_r, center_y - dot_r, center_x + dot_r, center_y + dot_r)
            self.canvas.itemconfig("phantom_hand_dot", fill="#222222")
        
        if self.wake_hour is not None:
            pct = self._energy_display_pct(current_e)
            if self.energy_curve._cached_e_max > 0:
                icon_size = max(12, int(radius / 7))
                tip_offset = icon_size + 10
                tx = center_x + (current_r + tip_offset) * math.cos(rad)
                ty = center_y - (current_r + tip_offset) * math.sin(rad)
                
                font_size = max(8, int(radius / 11))
                text_color = self.energy_curve.interpolate_color(current_e)
                
                # Manage 4-directional outline for legibility
                for i, (dx, dy) in enumerate([(-1, 0), (1, 0), (0, -1), (0, 1)]):
                    tag = f"phantom_hand_outline_{i}"
                    if not self.canvas.find_withtag(tag):
                        self.canvas.create_text(
                            tx + dx, ty + dy,
                            text=f"{pct}%",
                            fill="black",
                            font=("Segoe UI", font_size, "bold"),
                            tags=("phantom_hand", "phantom_hand_outline", tag)
                        )
                    else:
                        self.canvas.coords(tag, tx + dx, ty + dy)
                        self.canvas.itemconfig(tag, text=f"{pct}%", font=("Segoe UI", font_size, "bold"))

                if not self.canvas.find_withtag("phantom_hand_text"):
                    self.canvas.create_text(
                        tx, ty,
                        text=f"{pct}%",
                        fill=text_color,
                        font=("Segoe UI", font_size, "bold"),
                        tags=("phantom_hand", "phantom_hand_text")
                    )
                else:
                    self.canvas.coords("phantom_hand_text", tx, ty)
                    self.canvas.itemconfig("phantom_hand_text", text=f"{pct}%", fill=text_color, font=("Segoe UI", font_size, "bold"))
            else:
                self.canvas.delete("phantom_hand_text", "phantom_hand_outline")
        else:
            self.canvas.delete("phantom_hand_text", "phantom_hand_outline")

    def fetch_sun_times(self):
        def _fetch():
            lat, lon = None, None
            try:
                print("[ClockWidget] Fetching IP location from ip-api.com...")
                # Try HTTPS first, fallback to HTTP
                try:
                    req = urllib.request.Request("https://ip-api.com/json/?fields=lat,lon", headers={'User-Agent': 'Mozilla/5.0'})
                    resp = urllib.request.urlopen(req, timeout=3)
                except:
                    req = urllib.request.Request("http://ip-api.com/json/?fields=lat,lon", headers={'User-Agent': 'Mozilla/5.0'})
                    resp = urllib.request.urlopen(req, timeout=3)
                    
                data = json.loads(resp.read().decode())
                lat = data.get('lat')
                lon = data.get('lon')
                if lat is not None:
                    print(f"[ClockWidget] IP Location: {lat}, {lon}")
            except Exception as e:
                print(f"[ClockWidget] IP location failed ({e}), using timezone fallback.")
               
            if lat is None or lon is None:
                offset_hours = datetime.datetime.now().astimezone().utcoffset().total_seconds() / 3600.0
                tz_map = {
                    -12: (0.0, -180.0), -11: (-14.235, -170.559), -10: (21.3069, -157.8583), 
                    -9: (61.2181, -149.9003), -8: (34.0522, -118.2437), -7: (39.7392, -104.9903),
                    -6: (41.8781, -87.6298), -5: (40.7128, -74.0060), -4: (-33.4489, -70.6693),
                    -3: (-23.5505, -46.6333), -2: (0.0, -30.0), -1: (37.7412, -25.6756),
                    0: (51.5074, -0.1278), 1: (48.8566, 2.3522), 2: (30.0444, 31.2357),
                    3: (55.7558, 37.6173), 4: (25.2048, 55.2708), 5: (24.8607, 67.0011),
                    5.5: (28.6139, 77.2090), 6: (23.8103, 90.4125), 7: (13.7563, 100.5018),
                    8: (39.9042, 116.4074), 9: (35.6762, 139.6503), 10: (-33.8688, 151.2093),
                    11: (-36.8485, 174.7633), 12: (-41.2865, 174.7762)
                }
                closest = min(tz_map.keys(), key=lambda k: abs(k - offset_hours))
                lat, lon = tz_map[closest]
                print(f"[ClockWidget] Timezone fallback: {lat}, {lon} (Offset: {offset_hours})")

            try:
                Observer, sun, _, _, _ = _load_astral_dependencies()
                obs = Observer(latitude=lat, longitude=lon)
                # Pass the local timezone to astral to ensure sun events are found for 'today'
                local_tz = datetime.datetime.now().astimezone().tzinfo
                s = sun(obs, date=datetime.date.today(), tzinfo=local_tz)
               
                sunrise_dt = s['sunrise']
                sunset_dt = s['sunset']
               
                sunrise_hour = sunrise_dt.hour + sunrise_dt.minute / 60.0 + sunrise_dt.second / 3600.0
                sunset_hour = sunset_dt.hour + sunset_dt.minute / 60.0 + sunset_dt.second / 3600.0
                print(f"[ClockWidget] Sun times computed: {sunrise_hour:.2f} to {sunset_hour:.2f}")
            except Exception as e:
                print(f"[ClockWidget] Could not compute sunrise/sunset: {e}")
                sunrise_hour = None
                sunset_hour = None
            self._post_ui(self._apply_sun_times, lat, lon, sunrise_hour, sunset_hour)

        self._submit_background(_fetch)

    def _apply_sun_times(self, lat, lon, sunrise_hour, sunset_hour):
        changed = (lat, lon, sunrise_hour, sunset_hour) != (
            getattr(self, 'lat', None), getattr(self, 'lon', None),
            self.sunrise_hour, self.sunset_hour,
        )
        self.lat, self.lon = lat, lon
        self.sunrise_hour, self.sunset_hour = sunrise_hour, sunset_hour
        if changed:
            self._extremes_cache = None
            self._solar_peak_cache = None
            self._schedule_clock_redraw({
                "night_shading", "sun_and_moon", "ticks_and_numbers", "solar_circle"
            })

    def _get_fitbit_client_worker(self):
        if self.fitbit is None:
            from fitbit_client import FitbitClient
            self.fitbit = FitbitClient(
                client_id=self.FITBIT_CLIENT_ID,
                client_secret=self.FITBIT_CLIENT_SECRET,
            )
        return self.fitbit

    def relink_fitbit_and_refresh(self):
        """Re-authorize Fitbit OAuth, then force-fetch fresh sleep data."""
        def _task():
            try:
                print("[ClockWidget] Starting Fitbit re-authorization...")
                with self._fitbit_operation_lock:
                    self._get_fitbit_client_worker().authorize()
                print("[ClockWidget] Fitbit re-authorization succeeded — refreshing data.")
                self._post_ui(self._set_reauth_status, 'fitbit', False)
                self._post_ui(self.update_fitbit_data, True, "reauth")
            except Exception as e:
                print(f"[ClockWidget] Fitbit re-link failed: {e}")
                self._post_ui(self._set_reauth_status, 'fitbit', True)

        self._submit_background(_task)

    def update_fitbit_data(self, force: bool = False, reason: str = "scheduled"):
        """
        Background task to fetch latest Fitbit sleep & HR.
        Calls FitbitClient and updates self.fitbit_data.
        """
        if self._fitbit_refreshing:
            if force:
                self._fitbit_refresh_pending = True
                self._fitbit_pending_force = True
            return

        now_monotonic = time.monotonic()
        if not force and now_monotonic - self._fitbit_last_request_monotonic < 15.0:
            return

        self._fitbit_refreshing = True
        self._fitbit_last_request_monotonic = now_monotonic
        print(f"[ClockWidget] Fitbit refresh started ({reason}).")

        # Safely extract Tkinter variables on the main thread before starting the background task
        bedtime_goal = self.bedtime_goal.get()
        include_naps = self.include_naps.get()
        excluded_dates = list(self.excluded_dates) # Thread-safe copy
        explicit_dates = set(getattr(self, 'explicit_dates', ()))

        def _task():
            try:
                with self._fitbit_operation_lock:
                    inputs = self._get_fitbit_client_worker().get_all_energy_inputs(
                        bedtime_goal_hours=bedtime_goal,
                        include_naps=include_naps,
                        force=force,
                        excluded_dates=excluded_dates
                    )
                
                # If we got here, reauth is NOT needed
                self._post_ui(self._set_reauth_status, 'fitbit', False)

                # ... (rest of the logic)
                
                # Print the dynamic calculation for user visibility
                eff = inputs.get('empirical_efficiency', 1.0)
                need = inputs.get('sleep_need_hours', bedtime_goal)
                print(f"[Fitbit] Dynamic Efficiency: {eff:.1%}")
                print(f"[Fitbit] Dynamic Sleep Need: {need:.2f}h")

                # Collect results for main-thread application (no shared state mutation here)
                raw_logs = inputs.get('raw_sleep_logs', [])
                today_str = datetime.datetime.now().strftime("%Y-%m-%d")
                has_today_main = any(
                    log.get('dateOfSleep') == today_str and log.get('isMainSleep')
                    for log in raw_logs
                )

                has_saved_setting = today_str in explicit_dates

                should_save_image = (
                    (not inputs.get('from_cache', False) and inputs.get('is_real_today', False))
                    or not self._has_today_image()
                )

                # Schedule ALL state mutations on the main thread to avoid data races
                self._post_ui(
                    self._apply_fitbit_results,
                    inputs, today_str, has_today_main, has_saved_setting, should_save_image,
                )

            except Exception as e:
                if e.__class__.__name__ == "ReauthRequiredError":
                    print("[update_fitbit_data] Fitbit re-authentication required.")
                    self._post_ui(self._set_reauth_status, 'fitbit', True)
                else:
                    print(f"[update_fitbit_data] Fitbit background update failed: {e}")
            finally:
                def finish_refresh():
                    self._fitbit_refreshing = False
                    if self._fitbit_refresh_pending:
                        pending_force = self._fitbit_pending_force
                        self._fitbit_refresh_pending = False
                        self._fitbit_pending_force = False
                        self.update_fitbit_data(force=pending_force, reason="coalesced")
                self._post_ui(finish_refresh)
       
        self._submit_background(_task)

    def _apply_fitbit_results(self, inputs, today_str, has_today_main, has_saved_setting, should_save_image):
        """Queue the newest Fitbit snapshot for one main-thread apply pass."""
        self._pending_fitbit_results = (
            inputs, today_str, has_today_main, has_saved_setting, should_save_image
        )
        if self._fitbit_apply_id is None:
            self._fitbit_apply_id = self.root.after_idle(self._flush_fitbit_results)

    def _flush_fitbit_results(self):
        self._fitbit_apply_id = None
        pending = self._pending_fitbit_results
        self._pending_fitbit_results = None
        if pending is not None:
            self._apply_fitbit_results_now(*pending)

    def _apply_fitbit_results_now(self, inputs, today_str, has_today_main, has_saved_setting, should_save_image):
        """Apply Fitbit fetch results on the main thread (thread-safe)."""
        eff   = inputs.get('empirical_efficiency', 1.0)
        need  = inputs.get('sleep_need_hours', self.bedtime_goal.get())
        wake  = inputs.get('wake_hour')
        start = inputs.get('sleep_hour')
        dur   = inputs.get('sleep_duration')
        debt  = inputs.get('sleep_debt_hours', 0.0)
        bathy = inputs.get('bathyphase_hour')

        next_raw_logs = inputs.get('raw_sleep_logs', [])
        raw_signature = tuple(
            (
                log.get('dateOfSleep'), log.get('startTime'), log.get('endTime'),
                log.get('minutesAsleep'), log.get('isMainSleep'),
            )
            for log in next_raw_logs
        )
        next_signature = (
            wake, start, dur, debt, bathy, eff, need,
            inputs.get('active_sleep_date'), raw_signature,
        )
        data_changed = next_signature != getattr(self, '_fitbit_display_signature', None)
        self._fitbit_display_signature = next_signature
        self.raw_sleep_logs = next_raw_logs
        if data_changed:
            self._sleep_table_needs_refresh = True

        self._sync_sleep_inclusion(self.raw_sleep_logs)

        # Legacy today-only hook (explicit_dates bypass handled inside sync)
        if not has_saved_setting and has_today_main and today_str in self.excluded_dates:
            self.excluded_dates.remove(today_str)
            self._save_sleep_settings()

        try:
            from energy_logic import compute_sleep_debt
            debt = compute_sleep_debt(
                self.raw_sleep_logs,
                need,
                self.include_naps.get(),
                self.excluded_dates,
            )
        except Exception:
            pass

        if wake is not None:
            self.wake_hour          = wake
            self.sleep_hour         = start
            self.sleep_duration     = dur
            self.sleep_debt_hours   = debt if debt is not None else 0.0
            self.bathyphase_hour    = bathy
            self.sleep_efficiency   = eff
            self.sleep_need_hours   = need
            self.raw_sleep_logs     = next_raw_logs
            self.active_sleep_date  = inputs.get('active_sleep_date')

            self.last_fitbit_update = datetime.datetime.now()

            if data_changed:
                # Push main sleep and time-local naps into the adaptive model.
                self._apply_energy_curve_params()
                self.update_metric_labels()
                self.populate_sleep_table()
                self._schedule_clock_redraw({
                    "sleep_arc", "energy_curve", "sleep_debt_text",
                    "energy_indicators", "clock_hand",
                })

            if should_save_image and data_changed:
                self.root.after(1000, self.save_clock_image)
        else:
            print("[update_fitbit_data] wake_hour still None after fetch.")

    def update_calendar_data(self, reason: str = "scheduled"):
        """Fetch today's events from Google Calendar in the background."""
        if getattr(self, '_calendar_refreshing', False):
            print("[ClockWidget] Calendar refresh already in progress, skipping.")
            return
        
        self._calendar_refreshing = True
        print(f"[ClockWidget] Calendar refresh started ({reason}).")

        def _task():
            try:
                from google_calendar_client import (
                    get_calendar_service, get_calendar_events,
                )
                service = self.calendar_svc
                if not service:
                    try:
                        print("[ClockWidget] Initializing Google Calendar service...")
                        service = get_calendar_service()
                    except Exception as e:
                        print(f"[Calendar] Failed to initialize service: {e}")
                        return

                if not service:
                    print("[ClockWidget] Calendar service is None, aborting refresh.")
                    return
                
                print("[ClockWidget] Fetching calendar events...")
                events = get_calendar_events(service, 'primary')
                print(f"[Calendar] Successfully fetched {len(events)} events.")
                self._post_ui(self._apply_calendar_results, service, events)
            except Exception as e:
                if e.__class__.__name__ == "ReauthRequiredError":
                    print("[Calendar] Re-authentication required.")
                    self._post_ui(self._set_reauth_status, 'calendar', True)
                else:
                    print(f"[Calendar] Update failed: {e}")
            finally:
                self._post_ui(self._finish_calendar_refresh)
        
        self._submit_background(_task)

    @staticmethod
    def _calendar_signature(events):
        return tuple(
            (
                event.get('summary'), event.get('is_all_day'),
                event.get('start_dt'), event.get('end_dt'),
            )
            for event in events
        )

    def _apply_calendar_results(self, service, events):
        signature = self._calendar_signature(events)
        changed = signature != getattr(self, '_calendar_display_signature', None)
        self.calendar_svc = service
        self.calendar_events = events
        self._calendar_display_signature = signature
        self._last_calendar_refresh_time = datetime.datetime.now()
        self._set_reauth_status('calendar', False)
        if changed:
            self._schedule_clock_redraw({"calendar_events"})

    def _finish_calendar_refresh(self):
        self._calendar_refreshing = False

    def _build_sleep_table(self):
        """Build the styled sleep log inside its retained settings page."""
        ACCENT  = self.UI_ACCENT
        HDR_BG  = self.UI_SURFACE
        BG      = self.UI_BG

        outer = tk.Frame(self.sleep_table_window, bg=HDR_BG, bd=0)
        outer.pack(fill=tk.BOTH, expand=True)

        # Page heading (the containing settings window owns the drag bar).
        title_bar = tk.Frame(outer, bg=HDR_BG)
        title_bar.pack(fill=tk.X)
        title_lbl = tk.Label(
            title_bar, text="  Sleep log", bg=HDR_BG, fg=self.UI_TEXT,
            font=("Segoe UI", 11, "bold"), anchor="w", padx=6, pady=9
        )
        title_lbl.pack(side=tk.LEFT, fill=tk.X, expand=True)
        # Column specs: (header_text, pixel_width, data_anchor)
        self._table_cols = [
            ("Inc.",     35,  "center"),
            ("Day",      45,  "center"),
            ("Date",     65,  "e"),
            ("Start",    75,  "e"),
            ("End",      75,  "e"),
            ("Dur.",     45,  "e"),
            ("Eff.",     45,  "e"),
            ("Debt",     50,  "e"),
            ("Wtd.",     50,  "e"),
        ]

        # Single shared grid frame — headers at row 0, data below
        self.table_frame = tk.Frame(outer, bg=BG)
        self.table_frame.pack(fill=tk.BOTH, expand=True, padx=6, pady=(0, 6))
        self._sleep_table_width = sum(width for _, width, _ in self._table_cols) + 28
        self._table_row_pool = []
        self._table_separator = tk.Frame(self.table_frame, bg=self.UI_BORDER, height=1)

        for col_i, (hdr_text, px_width, _) in enumerate(self._table_cols):
            self.table_frame.columnconfigure(col_i, minsize=px_width, weight=0)
            lbl = tk.Label(
                self.table_frame, text=hdr_text,
                bg=HDR_BG, fg=ACCENT,
                font=("Segoe UI", 9, "bold"),
                anchor="center"
            )
            lbl.grid(row=0, column=col_i, sticky="ew", padx=1, pady=(4, 2))

        # Thin separator under headers
        sep = tk.Frame(self.table_frame, bg="#444444", height=1)
        sep.grid(row=1, column=0, columnspan=len(self._table_cols), sticky="ew")


    def _add_divider(self, text, parent):
        parent_bg = parent.cget("bg")
        if parent.winfo_children():
            sep = tk.Frame(parent, bg=self.UI_BORDER, height=1)
            sep.pack(fill=tk.X, pady=(9, 8))
            self._control_widgets.append(sep)
        
        lbl = tk.Label(
            parent, text=text.title(), bg=parent_bg, fg=self.UI_MUTED,
            font=("Segoe UI", 9, "bold"), anchor=tk.W,
        )
        lbl.pack(side=tk.TOP, anchor=tk.W, pady=(0, 4))
        self._control_widgets.append(lbl)

    def _add_toggle(self, text, var, cmd, parent, tooltip=None):
        parent_bg = parent.cget("bg")
        cb = tk.Checkbutton(
            parent,
            text=text,
            variable=var,
            command=cmd,
            bg=parent_bg,
            fg=self.UI_TEXT,
            selectcolor=self.UI_SURFACE_ALT,
            activebackground=parent_bg,
            activeforeground=self.UI_ACCENT_HOVER,
            highlightthickness=0,
            bd=0,
            padx=6,
            pady=4,
            anchor=tk.W,
            cursor="hand2",
            font=("Segoe UI", 9),
        )
        cb.pack(side=tk.TOP, fill=tk.X, anchor=tk.W)
        if tooltip:
            Tooltip(cb, tooltip)
        self._control_widgets.append(cb)
        return cb

    def _add_slider(self, text, var, from_, to, parent, resolution=0.1, command=None, tooltip=None):
        parent_bg = parent.cget("bg")
        frame = tk.Frame(parent, bg=parent_bg)
        frame.pack(side=tk.TOP, fill=tk.X, pady=2)
        lbl = tk.Label(frame, text=text, bg=parent_bg, fg=self.UI_TEXT, font=("Segoe UI", 9))
        lbl.pack(side=tk.TOP, anchor=tk.W)
        
        s = tk.Scale(
            frame, variable=var, from_=from_, to=to, resolution=resolution,
            orient=tk.HORIZONTAL, bg=parent_bg, fg=self.UI_MUTED,
            highlightthickness=0, troughcolor=self.UI_SURFACE_ALT, activebackground=self.UI_ACCENT,
            showvalue=True, font=("Segoe UI", 8), bd=0,
        )
        s.pack(side=tk.TOP, fill=tk.X, padx=5)
        if command:
            s.bind("<ButtonRelease-1>", lambda e: command())
        
        if tooltip:
            Tooltip(lbl, tooltip)
            Tooltip(s, tooltip)
            
        self._control_widgets.append(lbl)
        self._control_widgets.append(s)

    def _build_sleep_model(self):
        """Build the advanced model inside its retained settings page."""
        ACCENT  = self.UI_ACCENT
        HDR_BG  = self.UI_SURFACE
        BG      = self.UI_BG

        outer = tk.Frame(self.sleep_model_window, bg=HDR_BG, bd=0)
        outer.pack(fill=tk.BOTH, expand=True)

        # Page heading (the containing settings window owns the drag bar).
        title_bar = tk.Frame(outer, bg=HDR_BG)
        title_bar.pack(fill=tk.X)
        title_lbl = tk.Label(
            title_bar, text="  Advanced sleep model", bg=HDR_BG, fg=self.UI_TEXT,
            font=("Segoe UI", 11, "bold"), anchor="w", padx=6, pady=9
        )
        title_lbl.pack(side=tk.LEFT, fill=tk.X, expand=True)
        content = tk.Frame(outer, bg=BG)
        content.pack(fill=tk.BOTH, expand=True, padx=10, pady=(0, 10))

        def on_model_change():
            self._schedule_settings_refresh(recalculate_debt=True)
            
        def reset_model_defaults():
            self.bedtime_goal.set(9.75)
            self.circadian_offset.set(13.0)
            self.use_bathyphase.set(True)
            self.tau_wake.set(23.0)
            self.tau_sleep.set(4.0)
            self.tau_inertia.set(1.5)
            self.debt_factor.set(1.0)
            self.show_bathyphase.set(True)
            self.show_acrophase.set(True)
            on_model_change()

        self._add_divider("GENERAL GOALS", content)
        self._add_slider("Bedtime Goal (h):", self.bedtime_goal, 6.0, 12.0, content, command=on_model_change,
                         tooltip="Your target time in bed. Used to calculate 'Sleep Need' based on your average efficiency.")
        self._add_slider("Circadian Offset (h):", self.circadian_offset, 6.0, 16.0, content, command=on_model_change,
                         tooltip="Hours after wake when primary circadian alertness peaks. Adjusts the timing of your 'evening peak'.")
        self._add_toggle("Use Bathyphase HR", self.use_bathyphase, on_model_change, content,
                         tooltip="Use your actual lowest heart rate point (Bathyphase) to anchor your circadian curve.")
        
        self._add_divider("MODEL PARAMETERS", content)
        self._add_slider("Homeostatic Tau (Awake):", self.tau_wake, 10.0, 30.0, content, command=on_model_change,
                         tooltip="How fast you tire during the day. Higher values mean you stay alert for longer.")
        self._add_slider("Homeostatic Tau (Sleep):", self.tau_sleep, 2.0, 8.0, content, command=on_model_change,
                         tooltip="How fast you recover during sleep. Higher values mean sleep is less effective at discharging pressure.")
        self._add_slider("Sleep Inertia (h):", self.tau_inertia, 0.1, 4.0, content, command=on_model_change,
                         tooltip="Duration of morning grogginess. Controls how long it takes to reach full alertness after waking.")
        self._add_slider("Debt Sensitivity:", self.debt_factor, 0.0, 3.0, content, command=on_model_change,
                         tooltip="Multiplier for sleep debt penalty. Higher values make your curve sink lower when you lack sleep.")

        self._add_divider("VISUAL INDICATORS", content)
        self._add_toggle("Show Bathyphase (Trough)", self.show_bathyphase, on_model_change, content,
                         tooltip="Show an inward-pointing triangle at your lowest heart rate / energy trough.")
        self._add_toggle("Show Acrophase (Peak)", self.show_acrophase, on_model_change, content,
                         tooltip="Show an inward-pointing triangle at your highest predicted energy point today.")

        # Reset button
        reset_btn = self._settings_button(content, "Reset to defaults", reset_model_defaults, danger=True)
        reset_btn.pack(side=tk.BOTTOM, pady=(10, 0))

    def _recalculate_debt(self, *args):
        """Update sleep need and weighted debt based on current goals."""
        if not self.raw_sleep_logs: return
        
        # 1. Update sleep need based on current bedtime goal and efficiency
        eff = getattr(self, 'sleep_efficiency', 0.92) or 0.92
        self.sleep_need_hours = self.bedtime_goal.get() * eff
        
        # 2. Recalculate debt using the central logic
        try:
            from energy_logic import compute_sleep_debt
            new_debt = compute_sleep_debt(
                self.raw_sleep_logs, 
                self.sleep_need_hours, 
                self.include_naps.get(), 
                self.excluded_dates
            )
            self.sleep_debt_hours = new_debt
            self.energy_curve.sleep_debt_hours = new_debt
            
            # Refresh labels, table, and curve
            self.update_metric_labels()
            self.populate_sleep_table()
            self._schedule_clock_redraw({
                "energy_curve", "sleep_debt_text", "energy_indicators", "clock_hand"
            })
        except Exception as e:
            print(f"[_recalculate_debt] Error: {e}")


    def populate_sleep_table(self):
        """Populate the sleep table from self.raw_sleep_logs."""
        if not hasattr(self, 'table_frame') or not hasattr(self, '_table_cols'):
            return
        if (not getattr(self, 'sleep_table_page', None)
                or not self._settings_page_selected(self.sleep_table_page)):
            self._sleep_table_needs_refresh = True
            return

        started_at = time.perf_counter()

        TODAY_STR = datetime.datetime.now().strftime("%Y-%m-%d")
        ACCENT    = self.UI_ACCENT
        ROW_ODD   = self.UI_SURFACE_ALT
        ROW_EVN   = self.UI_SURFACE
        TODAY_BG  = "#293548"
        TODAY_FG  = "#aaddff"
        FG        = self.UI_TEXT
        NAP_FG    = "#9ecfb8"

        daily_logs: dict[str, list] = {}
        for rec in self.raw_sleep_logs:
            d = rec.get('dateOfSleep', '?')
            daily_logs.setdefault(d, []).append(rec)

        today_dt = datetime.datetime.now()
        display_dates = [
            (today_dt - datetime.timedelta(days=i)).strftime("%Y-%m-%d")
            for i in range(15)
        ]

        grid_row = 2
        total_raw = 0.0
        total_wtd = 0.0
        start_hours: list[float] = []
        end_hours: list[float] = []
        durations: list[float] = []
        efficiencies: list[float] = []

        def toggle_date(date_str, var):
            if not hasattr(self, 'explicit_dates'):
                self.explicit_dates = []
            if date_str not in self.explicit_dates:
                self.explicit_dates.append(date_str)
            if var.get():
                if date_str in self.excluded_dates:
                    self.excluded_dates.remove(date_str)
            else:
                if date_str not in self.excluded_dates:
                    self.excluded_dates.append(date_str)
            self._save_sleep_settings()
            try:
                from energy_logic import compute_sleep_debt
                new_debt = compute_sleep_debt(
                    self.raw_sleep_logs,
                    self.sleep_need_hours,
                    self.include_naps.get(),
                    self.excluded_dates,
                )
                self.sleep_debt_hours = new_debt
                self.energy_curve.sleep_debt_hours = new_debt
                self.update_metric_labels()
                self.populate_sleep_table()
                self._schedule_clock_redraw({
                    "sleep_arc", "energy_curve", "sleep_debt_text",
                    "energy_indicators", "clock_hand",
                })
            except Exception as e:
                print(f"[toggle_date] Instant refresh failed: {e}")
            self.update_fitbit_data()

        def fmt_clock(iso_str: str) -> tuple[str, float | None]:
            if not iso_str:
                return "?", None
            try:
                dt = datetime.datetime.fromisoformat(iso_str.replace('Z', ''))
                return dt.strftime("%I:%M %p").lstrip('0'), dt.hour + dt.minute / 60.0
            except Exception:
                return iso_str[:5], None

        def session_fields(session: dict) -> tuple[str, str, float, float | None]:
            start_fmt, _ = fmt_clock(session.get('startTime', ''))
            end_fmt, _ = fmt_clock(session.get('endTime', ''))
            mins_asleep = session.get('minutesAsleep', 0)
            mins_in_bed = session.get('timeIn_bed', session.get('timeInBed', 0))
            asleep_h = mins_asleep / 60.0
            eff_val = (mins_asleep / mins_in_bed * 100) if mins_in_bed > 0 else None
            return start_fmt, end_fmt, asleep_h, eff_val

        used_pool_rows = 0

        def acquire_row(row_idx, bg_color, include_state, cell_data, font):
            nonlocal used_pool_rows
            while len(self._table_row_pool) <= used_pool_rows:
                include_var = tk.BooleanVar(value=True)
                check = tk.Checkbutton(
                    self.table_frame,
                    variable=include_var,
                    bg=self.UI_SURFACE,
                    selectcolor=self.UI_SURFACE_ALT,
                    activebackground=self.UI_SURFACE,
                    highlightthickness=0,
                    bd=0,
                    padx=0,
                    pady=0,
                    cursor="hand2",
                )
                blank = tk.Label(self.table_frame, text="", bg=self.UI_SURFACE)
                labels = [
                    tk.Label(
                        self.table_frame, text="", bg=self.UI_SURFACE, fg=self.UI_TEXT,
                        font=("Segoe UI", 9), padx=6,
                    )
                    for _ in range(8)
                ]
                self._table_row_pool.append({
                    "include_var": include_var,
                    "check": check,
                    "blank": blank,
                    "labels": labels,
                })

            row = self._table_row_pool[used_pool_rows]
            used_pool_rows += 1
            check = row["check"]
            blank = row["blank"]
            if include_state is not None:
                date_str, is_included = include_state
                row["include_var"].set(is_included)
                check.configure(
                    command=lambda d=date_str, v=row["include_var"]: toggle_date(d, v),
                    bg=bg_color,
                    activebackground=bg_color,
                    selectcolor=self.UI_SURFACE_ALT,
                )
                blank.grid_remove()
                check.grid(row=row_idx, column=0, sticky="nsew")
            else:
                check.grid_remove()
                blank.configure(bg=bg_color)
                blank.grid(row=row_idx, column=0, sticky="nsew")

            for col_i, (label, (value, anchor, fg_color)) in enumerate(zip(row["labels"], cell_data)):
                label.configure(
                    text=value, bg=bg_color, fg=fg_color, font=font, anchor=anchor,
                )
                label.grid(row=row_idx, column=col_i + 1, sticky="ew", pady=1)

        def render_row(
            row_idx: int,
            bg_color: str,
            row_fg: str,
            debt_fg: str,
            include_state,
            day_fmt: str,
            date_fmt: str,
            start_fmt: str,
            end_fmt: str,
            dur_fmt: str,
            eff_fmt: str,
            debt_fmt: str,
            wtd_fmt: str,
            font: tuple = ("Segoe UI", 9),
        ) -> None:
            cell_data = [
                (day_fmt, "center", row_fg),
                (date_fmt, "e", row_fg),
                (start_fmt, "e", row_fg),
                (end_fmt, "e", row_fg),
                (dur_fmt, "e", row_fg),
                (eff_fmt, "e", row_fg),
                (debt_fmt, "e", debt_fg),
                (wtd_fmt, "e", debt_fg),
            ]
            acquire_row(row_idx, bg_color, include_state, cell_data, font)

        for i, date_str in enumerate(display_dates):
            is_excluded = date_str in self.excluded_dates
            bg_color = TODAY_BG if date_str == TODAY_STR else (ROW_ODD if i % 2 == 0 else ROW_EVN)
            row_fg = TODAY_FG if date_str == TODAY_STR else FG
            if is_excluded:
                row_fg = "#666666"

            try:
                dt_obj = datetime.datetime.strptime(date_str, "%Y-%m-%d")
                day_fmt = dt_obj.strftime("%a")
                date_fmt = dt_obj.strftime("%m/%d")
            except Exception:
                day_fmt = ""
                date_fmt = date_str

            logs = daily_logs.get(date_str, [])
            main_sleep = next((s for s in logs if s.get('isMainSleep')), None)
            naps = [s for s in logs if not s.get('isMainSleep')]

            include_state = (date_str, not is_excluded)

            if main_sleep:
                start_fmt, end_fmt, asleep_h, eff_val = session_fields(main_sleep)
                _, start_h = fmt_clock(main_sleep.get('startTime', ''))
                _, end_h = fmt_clock(main_sleep.get('endTime', ''))
                if not is_excluded:
                    if start_h is not None:
                        start_hours.append(start_h)
                    if end_h is not None:
                        end_hours.append(end_h)
                    durations.append(asleep_h)
                    if eff_val is not None:
                        efficiencies.append(eff_val)
                debt_val = self.sleep_need_hours - asleep_h
                wtd_val = debt_val * (0.9 ** i)
                if not is_excluded:
                    total_raw += debt_val
                    total_wtd += wtd_val
                debt_fg = "#ff6b6b" if debt_val > 0.1 else "#6bff6b" if debt_val < -0.1 else row_fg
                if is_excluded:
                    debt_fg = "#442222" if debt_val > 0.1 else "#224422"
                render_row(
                    grid_row, bg_color, row_fg, debt_fg, include_state,
                    day_fmt, date_fmt, start_fmt, end_fmt,
                    f"{asleep_h:.1f}h",
                    f"{eff_val:.0f}%" if eff_val is not None else "—",
                    f"{debt_val:+.1f}h", f"{wtd_val:+.1f}h",
                )
            else:
                debt_val = self.sleep_need_hours
                wtd_val = debt_val * (0.9 ** i)
                if not is_excluded:
                    total_raw += debt_val
                    total_wtd += wtd_val
                debt_fg = "#ff6b6b" if not is_excluded else "#442222"
                render_row(
                    grid_row, bg_color, row_fg, debt_fg, include_state,
                    day_fmt, date_fmt, "—", "—", "0.0h", "—",
                    f"{debt_val:+.1f}h", f"{wtd_val:+.1f}h",
                )

            grid_row += 1

            if self.include_naps.get() and naps and not is_excluded:
                for nap in sorted(naps, key=lambda s: s.get('startTime', '')):
                    nap_fg = "#666666" if is_excluded else NAP_FG
                    start_fmt, end_fmt, nap_h, eff_val = session_fields(nap)
                    nap_debt = -nap_h
                    nap_wtd = nap_debt * (0.9 ** i)
                    total_raw += nap_debt
                    total_wtd += nap_wtd
                    nap_debt_fg = "#6bff6b" if nap_h > 0.05 else row_fg
                    render_row(
                        grid_row, bg_color, nap_fg, nap_debt_fg, None,
                        "nap", date_fmt, start_fmt, end_fmt,
                        f"{nap_h:.1f}h",
                        f"{eff_val:.0f}%" if eff_val is not None else "—",
                        f"{nap_debt:+.1f}h", f"{nap_wtd:+.1f}h",
                        font=("Segoe UI", 8, "italic"),
                    )
                    grid_row += 1

        # Summary Row (Averages / Totals)
        self._table_separator.grid(
            row=grid_row, column=0, columnspan=len(self._table_cols), sticky="ew", pady=2
        )
        grid_row += 1
        
        # Circular mean helper for times
        def circ_avg(hours_list):
            if not hours_list: return "—"
            import math
            rads = [h * 2 * math.pi / 24 for h in hours_list]
            avg_sin = sum(math.sin(r) for r in rads) / len(rads)
            avg_cos = sum(math.cos(r) for r in rads) / len(rads)
            avg_rad = math.atan2(avg_sin, avg_cos)
            avg_h   = (avg_rad * 24 / (2 * math.pi)) % 24
            
            # Format to 12h
            hh = int(avg_h)
            mm = int((avg_h % 1) * 60)
            ampm = "AM" if hh < 12 else "PM"
            hh_12 = hh if 0 < hh <= 12 else (hh - 12 if hh > 12 else 12)
            if hh_12 == 0: hh_12 = 12
            return f"{hh_12}:{mm:02d} {ampm}"

        avg_start = circ_avg(start_hours)
        avg_end   = circ_avg(end_hours)
        avg_dur   = f"{sum(durations)/len(durations):.1f}h" if durations else "—"
        avg_eff   = f"{sum(efficiencies)/len(efficiencies):.0f}%" if efficiencies else "—"
        
        raw_fg = "#ff6b6b" if total_raw > 0.1 else "#6bff6b" if total_raw < -0.1 else ACCENT
        wtd_fg = "#ff6b6b" if total_wtd > 0.1 else "#6bff6b" if total_wtd < -0.1 else ACCENT
        
        summary_data = [
            ("AVG",     "center", ACCENT),
            ("",        "e", FG),
            (avg_start, "e", FG),
            (avg_end,   "e", FG),
            (avg_dur,   "e", FG),
            (avg_eff,   "e", FG),
            (f"{total_raw:+.1f}h", "e", raw_fg),
            (f"{total_wtd:+.1f}h", "e", wtd_fg),
        ]
        
        acquire_row(
            grid_row, self.UI_BG, None, summary_data, ("Segoe UI", 9, "bold")
        )

        for row in self._table_row_pool[used_pool_rows:]:
            row["check"].grid_remove()
            row["blank"].grid_remove()
            for label in row["labels"]:
                label.grid_remove()

        self._sleep_table_needs_refresh = False
        self._log_timing("sleep log refresh", started_at)

    @staticmethod
    def _format_clock_time(hour):
        h = int(hour) % 24
        m = int((hour % 1) * 60)
        am_pm = "AM" if h < 12 else "PM"
        h_display = h % 12 or 12
        return f"{h_display}:{m:02d} {am_pm}"

    def _predicted_acrophase_hour(self):
        """Return the clock hour of today's highest predicted energy level."""
        if self.wake_hour is None:
            return None
        self._apply_energy_curve_params()
        self.energy_curve.get_cached_energy(0)
        levels = self.energy_curve._cached_levels
        if not levels:
            return None
        peak_index = levels.index(self.energy_curve._cached_e_max)
        return (peak_index / (len(levels) - 1)) * 24.0

    def update_metric_labels(self):
        """Update the Bathyphase, Acrophase, and Efficiency labels in the controls window."""
        if not all(hasattr(self, name) for name in (
            'bathyphase_label', 'acrophase_label', 'efficiency_label',
        )):
            return
        if self.bathyphase_hour is not None:
            h = int(self.bathyphase_hour)
            m = int((self.bathyphase_hour - h) * 60)
            am_pm = "AM" if h < 12 else "PM"
            h_display = h % 12
            if h_display == 0: h_display = 12
            self.bathyphase_label.config(text=f"Bathyphase  ·  {h_display}:{m:02d} {am_pm}")
        else:
            self.bathyphase_label.config(text="Bathyphase  ·  --:-- --")
            
        acrophase_hour = self._predicted_acrophase_hour()
        if acrophase_hour is not None:
            self.acrophase_label.config(
                text=f"Predicted acrophase  ·  {self._format_clock_time(acrophase_hour)}"
            )
        else:
            self.acrophase_label.config(text="Predicted acrophase  ·  --:-- --")

        if self.sleep_efficiency is not None:
            self.efficiency_label.config(text=f"Sleep efficiency  ·  {self.sleep_efficiency*100:.1f}%")
        else:
            self.efficiency_label.config(text="Sleep efficiency  ·  --.-%")

    def show_initial(self):
        self.make_clock_transparent()
        self.root.deiconify()
        # Realize the borderless window before measuring the canvas. A draw
        # while withdrawn can see a 1x1 canvas and leave only later partial
        # layers painted.
        self.root.update_idletasks()
        self.draw_clock()
        self.root.after_idle(self._schedule_clock_redraw)
        self.root.after_idle(lambda: self._refresh_window_rect("root", self.root))
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._log_timing("first visible frame queued", self._launch_started)
        self.root.after_idle(self._start_background_services)

    def _start_background_services(self):
        """Begin non-critical work only after the clock has become visible."""
        self.fetch_sun_times()
        self.update_fitbit_data(reason="startup")
        self.update_calendar_data(reason="startup")
        self.update_bedtime_countdown_visibility(persist=False)
        self.root.after(75, self._ensure_controls_window)
        self.root.after(150, self.setup_tray_icon)

    def on_close(self):
        self._closing = True
        self._stop_bedtime_countdown_timer()
        if hasattr(self, 'tray_icon'):
            self.tray_icon.stop()
        self._executor.shutdown(wait=False, cancel_futures=True)
        self.root.destroy()

    def create_tray_image(self, now):
        from PIL import Image, ImageDraw
        image = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
       
        draw.ellipse((4, 4, 60, 60), fill="#ffffff", outline="black", width=2)
       
        if self.sunrise_hour is not None and self.sunset_hour is not None:
            night_hours = self.sunrise_hour - self.sunset_hour
            if night_hours < 0:
                night_hours += 24
               
            start_angle = (self.sunset_hour + 6) * 15
            extent = night_hours * 15
           
            draw.pieslice((4, 4, 60, 60), start=start_angle, end=start_angle + extent, fill="#2C3E50")
        draw.ellipse((4, 4, 60, 60), outline="black", width=1)
       
        current_hour = now.hour + now.minute / 60.0
        hand_angle = math.radians((current_hour + 6) * 15)
        hx = 32 + 22 * math.cos(hand_angle)
        hy = 32 + 22 * math.sin(hand_angle)
       
        draw.line((32, 32, hx, hy), fill="#FF9F1C", width=3)
       
        return image

    def show_widget(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()
        self.make_clock_solid()

    def toggle_widget(self):
        if self.root.winfo_viewable():
            self.root.withdraw()
        else:
            self.show_widget()

    def setup_tray_icon(self):
        if getattr(self, '_tray_initialized', False):
            return
        self._tray_initialized = True
        import pystray
        from PIL import ImageTk

        def on_quit(icon, item):
            icon.stop()
            self._post_ui(self.on_close)

        def on_toggle(icon, item=None):
            self._post_ui(self.toggle_widget)

        def on_settings(icon, item=None):
            self._post_ui(self.toggle_controls)
           
        def on_reauth(icon, item=None):
            self._post_ui(self.relink_fitbit_and_refresh)

        self.tray_menu = pystray.Menu(
            pystray.MenuItem('Show/Hide Clock', on_toggle, default=True),
            pystray.MenuItem('Settings', on_settings),
            pystray.MenuItem('Relink Fitbit', on_reauth),
            pystray.MenuItem('Exit', on_quit)
        )
       
        now = datetime.datetime.now()
        img = self.create_tray_image(now)
       
        self.tray_icon = pystray.Icon("ClockWidget", img, "24h Clock", self.tray_menu)
        self.tray_icon.action = on_toggle
        threading.Thread(target=self.tray_icon.run, daemon=True).start()
       
        try:
            self.tk_icon = ImageTk.PhotoImage(img)
            self.root.iconphoto(True, self.tk_icon)
        except Exception:
            pass

    def on_resize(self, event):
        if event.widget == self.canvas:
            new_size = (event.width, event.height)
            if getattr(self, '_last_canvas_size', None) != new_size:
                self._last_canvas_size = new_size
                if self._resize_redraw_id is not None:
                    self.root.after_cancel(self._resize_redraw_id)
                self._resize_redraw_id = self.root.after(35, self._flush_resize_redraw)

    def _flush_resize_redraw(self):
        self._resize_redraw_id = None
        self._schedule_clock_redraw()

    def update_clock(self):
        now = datetime.datetime.now()
        current_minute = now.hour * 60 + now.minute
        
        if not hasattr(self, '_last_drawn_minute'):
            self._last_drawn_minute = -1
        
        if self._last_drawn_minute != current_minute:
            # --- Periodic Data Refresh ---
            # 1. Midnight reset (sun times + full API refresh)
            if now.hour == 0 and now.minute == 0:
                print(f"[ClockWidget] Midnight reset at {now}")
                self.fetch_sun_times()
                self.update_fitbit_data(force=True, reason="midnight")
                self.update_calendar_data(reason="midnight")
            # 2. 10-minute polling (Fitbit + Calendar + Celestial)
            elif now.minute % 10 == 0:
                print(f"[ClockWidget] 10-minute polling refresh at {now}")
                self.update_fitbit_data(reason="poll")
                self.update_calendar_data(reason="poll")

            self._last_drawn_minute = current_minute
            if self.canvas.find_all():
                self.draw_clock({"calendar_events", "sun_and_moon", "solar_circle", "clock_hand"})
            else:
                self.draw_clock()
        else:
            self._update_hand_only(now)
            
        if not hasattr(self, 'last_tray_update'):
            self.last_tray_update = -1
        if now.minute % 5 == 0 and self.last_tray_update != now.minute:
            self.last_tray_update = now.minute
            img = self.create_tray_image(now)
            if hasattr(self, 'tray_icon') and self.tray_icon is not None:
                try:
                    self.tray_icon.icon = img
                except Exception:
                    pass
        seconds_to_next_mark = 10 - (now.second % 10)
        ms_to_next_mark = seconds_to_next_mark * 1000 - int(now.microsecond / 1000)
        self.root.after(max(100, ms_to_next_mark + 50), self.update_clock)

    def _update_hand_only(self, now):
        """Lightweight redraw: only move the clock hand between full redraws."""
        w = self.canvas.winfo_width()
        h = self.canvas.winfo_height()
        center_x, center_y = w / 2, h / 2
        radius = min(w, h) / 2 - self.FACE_PADDING
        if radius < 20:
            return
        
        self._draw_solar_circle(center_x, center_y, radius)
        self._draw_clock_hand(now, center_x, center_y, radius)

    def _apply_energy_curve_params(self):
        raw_signature = tuple(
            (
                log.get('dateOfSleep'), log.get('startTime'), log.get('endTime'),
                log.get('minutesAsleep'), log.get('isMainSleep'),
            )
            for log in self.raw_sleep_logs
        )
        signature = (
            self.wake_hour, self.sleep_debt_hours, self.sleep_duration,
            self.bathyphase_hour, self.active_sleep_date, raw_signature,
            self.show_sleep_debt.get(), self.include_naps.get(),
            self.tau_wake.get(), self.tau_sleep.get(), self.tau_inertia.get(),
            self.debt_factor.get(), self.circadian_offset.get(), self.use_bathyphase.get(),
        )
        if signature == getattr(self, '_energy_param_signature', None):
            return
        self._energy_param_signature = signature
        self.energy_curve.wake_hour = self.wake_hour
        self.energy_curve.sleep_debt_hours = self.sleep_debt_hours if self.show_sleep_debt.get() else 0.0
        main_duration, nap_intervals = sleep_model_context(
            self.raw_sleep_logs,
            self.active_sleep_date,
            self.include_naps.get(),
        )
        self.energy_curve.sleep_duration = (
            main_duration if main_duration is not None and main_duration > 0.0
            else self.sleep_duration
        )
        self.energy_curve.nap_intervals = nap_intervals
        self.energy_curve.bathyphase_hour = self.bathyphase_hour if self.use_bathyphase.get() else None
        
        self.energy_curve.tau_wake = self.tau_wake.get()
        self.energy_curve.tau_sleep = self.tau_sleep.get()
        self.energy_curve.tau_inertia = self.tau_inertia.get()
        self.energy_curve.debt_factor = self.debt_factor.get()
        self.energy_curve.circadian_peak_offset = None if self.use_bathyphase.get() else self.circadian_offset.get()

    def _draw_clock_hand(self, now, center_x, center_y, radius):
        current_hour = now.hour + now.minute / 60.0 + now.second / 3600.0
        hand_angle = (18 - current_hour) * 15
        hand_rad = math.radians(hand_angle)
       
        hx = center_x + radius * math.cos(hand_rad)
        hy = center_y - radius * math.sin(hand_rad)
       
        arrow_len = radius * 0.12
        hand_width = max(2, int(radius/25))
        line_items = self.canvas.find_withtag("clock_hand_line")
        if line_items:
            hand_line = line_items[0]
            self.canvas.coords(hand_line, center_x, center_y, hx, hy)
            self.canvas.itemconfigure(
                hand_line, width=hand_width,
                arrowshape=(arrow_len, arrow_len, arrow_len/3),
            )
        else:
            self.canvas.create_line(
                center_x, center_y, hx, hy,
                fill="#FF9F1C", width=hand_width,
                arrow=tk.LAST, arrowshape=(arrow_len, arrow_len, arrow_len/3),
                capstyle=tk.ROUND,
                tags=("clock_hand", "clock_hand_line"),
            )
        
        # Add a circular cap at the center for a perfectly rounded terminus
        dot_r = hand_width / 2
        dot_items = self.canvas.find_withtag("clock_hand_dot")
        if dot_items:
            self.canvas.coords(
                dot_items[0], center_x - dot_r, center_y - dot_r,
                center_x + dot_r, center_y + dot_r,
            )
        else:
            self.canvas.create_oval(
                center_x - dot_r, center_y - dot_r,
                center_x + dot_r, center_y + dot_r,
                fill="#FF9F1C", outline="",
                tags=("clock_hand", "clock_hand_dot"),
            )
        
        if self.wake_hour is not None:
            self._apply_energy_curve_params()
            current_e = self.energy_curve.get_cached_energy(current_hour)

            if self.energy_curve._cached_e_max > 0 and self.show_energy_pct.get():
                pct = self._energy_display_pct(current_e)
                # Text at the tip of the hand
                icon_size = max(12, int(radius / 7))
                tip_offset = icon_size + 10
                tx = center_x + (radius + tip_offset) * math.cos(hand_rad)
                ty = center_y - (radius + tip_offset) * math.sin(hand_rad)
                
                font_size = max(8, int(radius / 11))
                text_color = self.energy_curve.interpolate_color(current_e)
                
                text_value = f"{pct}%"
                for index, (dx, dy) in enumerate([(-1, 0), (1, 0), (0, -1), (0, 1)]):
                    tag = f"clock_hand_pct_outline_{index}"
                    items = self.canvas.find_withtag(tag)
                    if items:
                        item_id = items[0]
                        self.canvas.coords(item_id, tx + dx, ty + dy)
                        self.canvas.itemconfigure(item_id, text=text_value, font=("Segoe UI", font_size, "bold"))
                    else:
                        self.canvas.create_text(
                            tx + dx, ty + dy, text=text_value, fill="black",
                            font=("Segoe UI", font_size, "bold"),
                            tags=("clock_hand", "clock_hand_pct", tag),
                        )

                pct_items = self.canvas.find_withtag("clock_hand_pct_text")
                if pct_items:
                    self.canvas.coords(pct_items[0], tx, ty)
                    self.canvas.itemconfigure(
                        pct_items[0], text=text_value, fill=text_color,
                        font=("Segoe UI", font_size, "bold"),
                    )
                else:
                    self.canvas.create_text(
                        tx, ty, text=text_value, fill=text_color,
                        font=("Segoe UI", font_size, "bold"),
                        tags=("clock_hand", "clock_hand_pct", "clock_hand_pct_text"),
                    )
            else:
                self.canvas.delete("clock_hand_pct")
        else:
            self.canvas.delete("clock_hand_pct")

    def _get_celestial_positions(self, current_hour):
        """Return (sun_rad, moon_rad, m_phase_val, sun_elev), calculating current elevation every minute but caching extremes for 5 minutes."""
        now = datetime.datetime.now()
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        
        # 1. Update extremes cache if needed (every 5 minutes)
        if not hasattr(self, '_extremes_cache') or (now - self._extremes_cache['ts']).total_seconds() >= 300:
            try:
                user_lat = getattr(self, 'lat', None)
                user_lon = getattr(self, 'lon', None)
                if user_lat is None or user_lon is None:
                    raise ValueError("Lat/Lon not yet available")

                Observer, _, sun_elevation, phase, moon_elevation = _load_astral_dependencies()
                obs = Observer(latitude=user_lat, longitude=user_lon)
                times = [now_utc + datetime.timedelta(hours=h) for h in range(-12, 13)]
                
                s_elevs = [sun_elevation(obs, t) for t in times]
                m_elevs = [moon_elevation(obs, t) for t in times]
                
                self._extremes_cache = {
                    's_max': max(s_elevs),
                    's_min': min(s_elevs),
                    'm_max': max(m_elevs),
                    'm_min': min(m_elevs),
                    'm_phase': phase(datetime.date.today()),
                    'ts': now,
                    'obs': obs
                }
            except Exception:
                return self._fallback_celestial_positions(current_hour, now_utc)

        # 2. Calculate current positions every minute using cached extremes
        _, _, sun_elevation, _, moon_elevation = _load_astral_dependencies()
        cache = self._extremes_cache
        obs = cache['obs']
        
        def get_current_mapped_angle(body='sun'):
            if body == 'sun':
                cur_e = sun_elevation(obs, now_utc)
                next_e = sun_elevation(obs, now_utc + datetime.timedelta(seconds=1))
                e_max, e_min = cache['s_max'], cache['s_min']
            else:
                cur_e = moon_elevation(obs, now_utc)
                next_e = moon_elevation(obs, now_utc + datetime.timedelta(seconds=1))
                e_max, e_min = cache['m_max'], cache['m_min']
            
            is_rising = next_e > cur_e
            
            if cur_e >= 0:
                val = min(1.0, max(-1.0, cur_e / (e_max if e_max > 0 else 1.0)))
                angle = math.degrees(math.asin(val))
                if is_rising: angle = 180 - angle
            else:
                val = min(1.0, max(-1.0, cur_e / abs(e_min if e_min < 0 else -1.0)))
                angle = math.degrees(math.asin(val))
                if is_rising: angle = 180 - angle
            return angle % 360

        s_rad = math.radians(get_current_mapped_angle('sun'))
        m_rad = math.radians(get_current_mapped_angle('moon'))
        m_phase_val = cache['m_phase']
        sun_elev = sun_elevation(obs, now_utc)
        
        return s_rad, m_rad, m_phase_val, sun_elev

    @staticmethod
    def _fallback_celestial_positions(current_hour, now_utc=None):
        """Return a stable time/phase approximation until location data is ready."""
        if now_utc is None:
            now_utc = datetime.datetime.now(datetime.timezone.utc)
        elif now_utc.tzinfo is None:
            now_utc = now_utc.replace(tzinfo=datetime.timezone.utc)

        # A well-known new-moon epoch keeps the fallback moon phase moving
        # instead of permanently stacking an invisible new moon under the sun.
        new_moon_epoch = datetime.datetime(
            2000, 1, 6, 18, 14, tzinfo=datetime.timezone.utc
        )
        elapsed_days = (now_utc - new_moon_epoch).total_seconds() / 86400.0
        phase_days = elapsed_days % SYNODIC_MONTH_DAYS
        phase_fraction = phase_days / SYNODIC_MONTH_DAYS

        sun_rad = math.radians((18 - current_hour) * 15)
        moon_hour = (current_hour - phase_fraction * 24.0) % 24.0
        moon_rad = math.radians((18 - moon_hour) * 15)
        # Approximate elevation only controls marker color in fallback mode:
        # 0 at 06:00/18:00, positive by day, negative by night.
        sun_elev = 60.0 * math.sin(math.radians((current_hour - 6.0) * 15.0))
        return sun_rad, moon_rad, phase_days, sun_elev

    @staticmethod
    def _celestial_marker_geometry(center_x, center_y, radius, sun_rad, moon_rad):
        """Calculate the outer-orbit geometry shared by rendering and tests."""
        icon_size = max(12, int(radius / 7))
        orbit_radius = radius + icon_size + 2
        marker_radius = icon_size / 1.6
        return {
            "icon_size": icon_size,
            "orbit_radius": orbit_radius,
            "marker_radius": marker_radius,
            "sun_center": (
                center_x + orbit_radius * math.cos(sun_rad),
                center_y - orbit_radius * math.sin(sun_rad),
            ),
            "moon_center": (
                center_x + orbit_radius * math.cos(moon_rad),
                center_y - orbit_radius * math.sin(moon_rad),
            ),
        }

    def _draw_celestial_markers(
            self, center_x, center_y, radius,
            sun_rad, moon_rad, moon_phase_value, sun_elevation):
        """Draw visible sun and moon markers using the shared outer orbit."""
        geometry = self._celestial_marker_geometry(
            center_x, center_y, radius, sun_rad, moon_rad
        )
        marker_radius = geometry["marker_radius"]
        sx, sy = geometry["sun_center"]
        mx, my = geometry["moon_center"]

        sun_fill, _sun_alpha, sun_outline = self._interpolate_sun_color(sun_elevation)
        self.canvas.create_oval(
            sx - marker_radius, sy - marker_radius,
            sx + marker_radius, sy + marker_radius,
            fill=sun_fill, outline=sun_outline, width=2,
            tags="sun_and_moon",
        )

        moon_bounds = (
            mx - marker_radius, my - marker_radius,
            mx + marker_radius, my + marker_radius,
        )
        self.canvas.create_oval(
            *moon_bounds, fill=MOON_SHADOW_COLOR, outline="", tags="sun_and_moon"
        )

        phase_fraction = (moon_phase_value / SYNODIC_MONTH_DAYS) % 1.0
        if 0.01 < phase_fraction < 0.99:
            if phase_fraction <= 0.5:
                self.canvas.create_arc(
                    *moon_bounds, start=-90, extent=180,
                    fill=MOON_LIT_COLOR, outline="", style=tk.PIESLICE,
                    tags="sun_and_moon",
                )
                mid_phase = phase_fraction * 4.0 - 1.0
                ellipse_color = (
                    MOON_SHADOW_COLOR if mid_phase < 0 else MOON_LIT_COLOR
                )
            else:
                self.canvas.create_arc(
                    *moon_bounds, start=90, extent=180,
                    fill=MOON_LIT_COLOR, outline="", style=tk.PIESLICE,
                    tags="sun_and_moon",
                )
                mid_phase = (phase_fraction - 0.5) * 4.0 - 1.0
                ellipse_color = (
                    MOON_LIT_COLOR if mid_phase < 0 else MOON_SHADOW_COLOR
                )
            ellipse_half_width = abs(mid_phase) * marker_radius
            self.canvas.create_oval(
                mx - ellipse_half_width, my - marker_radius,
                mx + ellipse_half_width, my + marker_radius,
                fill=ellipse_color, outline="", tags="sun_and_moon",
            )

        return geometry

    def _get_solar_irradiance(self):
        """Return solar irradiance brightness 0-255 based on current sun elevation.
        
        Uses Beer-Lambert approximation for atmospheric attenuation,
        applies Gamma correction for human perception, and models twilight.
        """
        try:
            user_lat = getattr(self, 'lat', None)
            user_lon = getattr(self, 'lon', None)
            if user_lat is None or user_lon is None:
                raise ValueError("Lat/Lon not available")

            Observer, _, sun_elevation, _, _ = _load_astral_dependencies()
            now_utc = datetime.datetime.now(datetime.timezone.utc)
            obs = Observer(latitude=user_lat, longitude=user_lon)
            elev = sun_elevation(obs, now_utc)   # degrees, negative when below horizon

            sunset_intensity = 99.0 / 255.0 # Hex #636363

            if elev >= 0:
                # Theoretical peak elevation for today (sample every 30 min)
                if not hasattr(self, '_solar_peak_cache') or \
                   (datetime.datetime.now() - self._solar_peak_cache['ts']).total_seconds() >= 3600:
                    times = [now_utc + datetime.timedelta(minutes=30 * i) for i in range(48)]
                    elevs = [sun_elevation(obs, t) for t in times]
                    peak = max(elevs)
                    self._solar_peak_cache = {'peak': peak, 'ts': datetime.datetime.now()}
                else:
                    peak = self._solar_peak_cache['peak']

                if peak <= 0:
                    return int(round(255 * sunset_intensity))

                # 1. Physics: Irradiance ∝ sin(elevation) * exp(-k / sin(elevation))
                # k = 0.12 is a typical clear-sky extinction coefficient
                k = 0.12
                def get_irradiance(e):
                    # Ensure sin(e) is not zero to avoid division by zero in exp
                    s = math.sin(math.radians(max(0.01, e)))
                    return s * math.exp(-k / s)
                    
                raw = get_irradiance(elev)
                peak_raw = get_irradiance(peak)
                
                # 2. Normalise 0.0 - 1.0
                ratio = max(0, min(1.0, raw / peak_raw))
                
                # 3. Perception: Gamma Correction (sRGB standard is ~2.2)
                gamma = 2.2
                corrected_ratio = math.pow(ratio, 1/gamma)
                
                # Map [0, 1] to [sunset_intensity, 1.0]
                final_intensity = sunset_intensity + (1.0 - sunset_intensity) * corrected_ratio
                brightness = int(round(255 * final_intensity))
                return max(0, min(255, brightness))
            else:
                # Twilight: Sun below horizon
                # Threshold for complete darkness (approx 30-45 mins at mid-latitudes)
                darkness_elev = -8.0
                if elev <= darkness_elev:
                    return 0
                
                # Ratio goes from 1.0 at horizon (elev=0) to 0.0 at darkness_elev
                ratio = (elev - darkness_elev) / (0.0 - darkness_elev)
                # Faster decay using quadratic function
                corrected_ratio = math.pow(max(0, min(1.0, ratio)), 2.0)
                
                final_intensity = sunset_intensity * corrected_ratio
                brightness = int(round(255 * final_intensity))
                return max(0, min(255, brightness))
        except Exception:
            return 0

    def _draw_solar_circle(self, center_x, center_y, radius):
        """Draw the solar irradiance indicator circle at the clock center."""
        brightness = self._get_solar_irradiance()
        circle_r = radius * (2/13)

        # Colour: pure black at 0 brightness to pure white at 255 brightness
        val = int(brightness)
        fill_color = f"#{val:02x}{val:02x}{val:02x}"


        circle_items = self.canvas.find_withtag("solar_circle")
        if circle_items:
            self.canvas.coords(
                circle_items[0], center_x - circle_r, center_y - circle_r,
                center_x + circle_r, center_y + circle_r,
            )
            self.canvas.itemconfigure(circle_items[0], fill=fill_color)
        else:
            self.canvas.create_oval(
                center_x - circle_r, center_y - circle_r,
                center_x + circle_r, center_y + circle_r,
                fill=fill_color,
                outline="",
                width=1,
                tags="solar_circle"
            )

    def _interpolate_sun_color(self, elevation):
        """Return (fill_hex, alpha_0_255, outline_hex) based on sun elevation."""
        darkest_elev = -8.0
        
        if elevation >= 0:
            # Daytime: Fully opaque
            t = max(0.0, min(1.0, elevation / 20.0))
            r = 255
            g = int(69 * (1 - t) + 215 * t)
            b = 0
            fill = f"#{r:02x}{g:02x}{b:02x}"
            
            # Outline stays a bit more orange than fill
            og = int(40 * (1 - t) + 165 * t)
            outline = f"#{r:02x}{og:02x}{b:02x}"
            return fill, 255, outline
        else:
            # Night/Twilight: Keep alpha fully visible (255) so the sun is clearly yellow and visible.
            ratio = max(0.0, min(1.0, (elevation - darkest_elev) / (0.0 - darkest_elev)))
            alpha = 255 # Opaque yellow/gold sun at night
            
            # Transition from Deep Orange (#FF4500) at horizon to Gold/Yellow (#FFD700) at night
            r = 255
            g = int(215 * (1 - ratio) + 69 * ratio)
            b = 0
            
            fill = f"#{r:02x}{g:02x}{b:02x}"
            # Outline matches fill at night to prevent the "ring" look
            og = int(215 * (1 - ratio) + 40 * ratio)
            outline = f"#{r:02x}{og:02x}{b:02x}"
            return fill, alpha, outline

    def on_canvas_motion(self, event):
        """Handle tooltip display for calendar events."""
        self._update_resize_cursor(event)

        item = self.canvas.find_withtag("current")
        item_id = item[0] if item else None
        if item_id not in self._calendar_item_map:
            self._hide_calendar_tooltip()
            return

        now_monotonic = time.monotonic()
        if (item_id == self._tooltip_event_item
                and now_monotonic - self._last_tooltip_motion < 0.03):
            return
        self._last_tooltip_motion = now_monotonic
        self._tooltip_event_item = item_id
        summary = self._calendar_item_map[item_id]

        if self._tooltip_id:
            self.canvas.coords(self._tooltip_id, event.x, event.y - 20)
            self.canvas.itemconfigure(self._tooltip_id, text=summary)
        else:
            self._tooltip_id = self.canvas.create_text(
                event.x, event.y - 20,
                text=summary,
                fill="white",
                font=("Segoe UI", 9, "bold"),
                anchor=tk.S,
                state=tk.DISABLED # Transparent to clicks
            )

        bbox = self.canvas.bbox(self._tooltip_id)
        if bbox:
            coords = (bbox[0]-4, bbox[1]-2, bbox[2]+4, bbox[3]+2)
            if self._tooltip_bg_id:
                self.canvas.coords(self._tooltip_bg_id, *coords)
            else:
                self._tooltip_bg_id = self.canvas.create_rectangle(
                    *coords,
                    fill="#1a1a1a", outline="#9370DB", width=1,
                    state=tk.DISABLED # Transparent to clicks
                )
            self.canvas.tag_raise(self._tooltip_bg_id)
            self.canvas.tag_raise(self._tooltip_id)

    def _hide_calendar_tooltip(self):
        if self._tooltip_id:
            self.canvas.delete(self._tooltip_id)
        if self._tooltip_bg_id:
            self.canvas.delete(self._tooltip_bg_id)
        self._tooltip_id = None
        self._tooltip_bg_id = None
        self._tooltip_event_item = None

    def _update_resize_cursor(self, event):
        if self.is_clock_transparent:
            cursor = ""
        else:
            margin = 12
            width = max(1, self.canvas.winfo_width())
            height = max(1, self.canvas.winfo_height())
            left, right = event.x <= margin, event.x >= width - margin
            top, bottom = event.y <= margin, event.y >= height - margin
            if (left and top) or (right and bottom):
                cursor = "size_nw_se"
            elif (right and top) or (left and bottom):
                cursor = "size_ne_sw"
            elif left or right:
                cursor = "sb_h_double_arrow"
            elif top or bottom:
                cursor = "sb_v_double_arrow"
            else:
                cursor = "fleur"
        try:
            self.canvas.configure(cursor=cursor)
        except tk.TclError:
            self.canvas.configure(cursor="")

    def _set_reauth_status(self, service, needed):
        attr = 'reauth_needed_fitbit' if service == 'fitbit' else 'reauth_needed_calendar'
        if getattr(self, attr) == needed:
            return
        setattr(self, attr, needed)
        self._schedule_clock_redraw({"reauth_indicators"})

    def draw_clock(self, dirty_layers=None):
        """Draw all layers, or update only the requested retained layers."""
        # The full compositor remains the correctness path. The retained
        # hand-only update still handles the frequent 10-second tick, while
        # promoting invalidated layer requests avoids overlays disappearing
        # when a later redraw changes the canvas tag order.
        dirty_layers = None
        full_redraw = dirty_layers is None
        requested_layers = set(self.DRAW_ORDER if full_redraw else dirty_layers)
        if full_redraw:
            self.canvas.delete("all")
            self.energy_curve._line_ids = []
            self._calendar_item_map.clear()
            self._tooltip_id = None
            self._tooltip_bg_id = None
            self._tooltip_event_item = None
        else:
            if "calendar_events" in requested_layers:
                self._hide_calendar_tooltip()
                self._calendar_item_map.clear()
            retained = {"energy_curve", "solar_circle", "clock_hand"}
            for layer_name in requested_layers:
                preserve_items = layer_name in retained
                if layer_name == "energy_curve" and (
                        not self.show_energy.get() or self.wake_hour is None):
                    preserve_items = False
                if not preserve_items:
                    self.canvas.delete(f"layer:{layer_name}")
                    if layer_name == "energy_curve":
                        self.canvas.delete("energy_curve")
                        self.energy_curve._line_ids = []

        w = self.canvas.winfo_width()
        h = self.canvas.winfo_height()
       
        if w < 10 or h < 10:
            if self.root.winfo_viewable() and self._layout_retry_id is None:
                self._layout_retry_id = self.root.after(50, self._retry_full_clock_draw)
            return
           
        center_x = w / 2
        center_y = h / 2
        # --- UPDATE RADIUS PADDING ---
        # Reduced padding to give just enough space for outer elements
        radius = min(w, h) / 2 - self.FACE_PADDING
       
        if radius < 20:
            return

        def draw_background_face():
            self.canvas.create_oval(
                center_x - radius, center_y - radius,
                center_x + radius, center_y + radius,
                fill="#ffffff", outline="black", width=3
            )
           
        def draw_night_shading():
            if self.sunrise_hour is None or self.sunset_hour is None:
                return
            night_hours = self.sunrise_hour - self.sunset_hour
            if night_hours < 0:
                night_hours += 24
               
            sunset_angle = (18 - self.sunset_hour) * 15
            extent = - (night_hours * 15)
           
            self.canvas.create_arc(
                center_x - radius, center_y - radius,
                center_x + radius, center_y + radius,
                start=sunset_angle, extent=extent,
                fill="#2C3E50", outline="", style=tk.PIESLICE
            )
           
        def draw_sleep_arc():
            if not self.show_sleep.get() or not self.raw_sleep_logs or not self.active_sleep_date:
                return

            # Draw every sleep session for the active date (main sleep + naps)
            for log in self.raw_sleep_logs:
                if log.get('dateOfSleep') != self.active_sleep_date:
                    continue
                
                # Check if this specific log should be included based on "Include Naps" toggle
                if not self.include_naps.get() and not log.get('isMainSleep', False):
                    continue

                try:
                    start_dt = datetime.datetime.fromisoformat(log['startTime'].replace('Z', ''))
                    end_dt   = datetime.datetime.fromisoformat(log['endTime'].replace('Z', ''))
                    
                    # Use 'timeInBed' for the arc if "Time in Bed" is toggled, 
                    # otherwise we'd need 'asleep' intervals which Fitbit only provides in 'levels' data.
                    # For now, we use the full startTime/endTime window.
                    
                    s_h = start_dt.hour + start_dt.minute / 60.0 + start_dt.second / 3600.0
                    e_h = end_dt.hour + end_dt.minute / 60.0 + end_dt.second / 3600.0
                    
                    # If we aren't showing total bedtime, we should adjust the start time 
                    # based on the minutes asleep. This is a simplification but works for the arc.
                    if not self.show_total_bedtime.get():
                        dur_hrs = log.get('minutesAsleep', 0) / 60.0
                        s_h = (e_h - dur_hrs) % 24
                        
                    display_dur = e_h - s_h
                    if display_dur < 0:
                        display_dur += 24
                        
                    sleep_start_angle = (18 - s_h) * 15
                    sleep_extent = - (display_dur * 15)
                    
                    sleep_margin = radius * 0.15
                    # Use a slightly different color or transparency for naps? 
                    # For now, keep it consistent but maybe slightly lighter for naps.
                    is_main = log.get('isMainSleep', False)
                    arc_color = "#6A5ACD" if is_main else "#8A7AED"
                    
                    self.canvas.create_arc(
                        center_x - (radius - sleep_margin), center_y - (radius - sleep_margin),
                        center_x + (radius - sleep_margin), center_y + (radius - sleep_margin),
                        start=sleep_start_angle, extent=sleep_extent,
                        fill=arc_color, outline="", style=tk.PIESLICE,
                        tags="sleep_arc"
                    )
                except (KeyError, ValueError):
                    continue

            # Draw grogginess wedge for the main sleep
            main_sleep = next((log for log in self.raw_sleep_logs if log.get('dateOfSleep') == self.active_sleep_date and log.get('isMainSleep', False)), None)
            if self.show_grogginess.get() and main_sleep:
                try:
                    end_dt = datetime.datetime.fromisoformat(main_sleep['endTime'].replace('Z', ''))
                    wake_h = end_dt.hour + end_dt.minute / 60.0 + end_dt.second / 3600.0
                    
                    g_start_angle = (18 - wake_h) * 15
                    g_extent = 1.5 * 15  # 22.5 degrees (1 hr 30 min)
                    
                    sleep_margin = radius * 0.15
                    num_steps = 10
                    for i in range(num_steps):
                        t = i / (num_steps - 1)
                        # Interpolate from #555555 (85,85,85) to #EEEEEE (238,238,238)
                        val = int(85 + (238 - 85) * t)
                        slice_color = f"#{val:02x}{val:02x}{val:02x}"
                        
                        step_size = g_extent / num_steps
                        s_angle = g_start_angle - i * step_size
                        
                        self.canvas.create_arc(
                            center_x - (radius - sleep_margin), center_y - (radius - sleep_margin),
                            center_x + (radius - sleep_margin), center_y + (radius - sleep_margin),
                            start=s_angle, extent=-step_size,
                            fill=slice_color, outline=slice_color, style=tk.PIESLICE,
                            tags="sleep_arc"
                        )
                except (KeyError, ValueError):
                    pass

            # Draw wind-down wedge before the main sleep
            if self.show_wind_down.get() and main_sleep:
                try:
                    start_dt = datetime.datetime.fromisoformat(main_sleep['startTime'].replace('Z', ''))
                    end_dt   = datetime.datetime.fromisoformat(main_sleep['endTime'].replace('Z', ''))
                    
                    asleep_h = start_dt.hour + start_dt.minute / 60.0 + start_dt.second / 3600.0
                    if not self.show_total_bedtime.get():
                        dur_hrs = main_sleep.get('minutesAsleep', 0) / 60.0
                        e_h = end_dt.hour + end_dt.minute / 60.0 + end_dt.second / 3600.0
                        asleep_h = (e_h - dur_hrs) % 24

                    # 90 minutes (1.5h) wind-down leading into sleep start
                    wd_start_h = (asleep_h - 1.5) % 24
                    wd_start_angle = (18 - wd_start_h) * 15
                    wd_extent = 1.5 * 15

                    sleep_margin = radius * 0.15
                    num_steps = 10
                    for i in range(num_steps):
                        t = i / (num_steps - 1)
                        # Interpolate light gray #5C5C5C (92) to dark gray #111111 (17)
                        val = int(92 + (17 - 92) * t)
                        slice_color = f"#{val:02x}{val:02x}{val:02x}"
                        
                        step_size = wd_extent / num_steps
                        s_angle = wd_start_angle - i * step_size
                        
                        self.canvas.create_arc(
                            center_x - (radius - sleep_margin), center_y - (radius - sleep_margin),
                            center_x + (radius - sleep_margin), center_y + (radius - sleep_margin),
                            start=s_angle, extent=-step_size,
                            fill=slice_color, outline=slice_color, style=tk.PIESLICE,
                            tags="sleep_arc"
                        )
                except (KeyError, ValueError):
                    pass
           
        def draw_energy_curve():
            if self.show_energy.get() and self.wake_hour is not None:
                self._apply_energy_curve_params()
                self.energy_curve.normalize = self.normalize_energy.get()
                self.energy_curve.draw(center_x, center_y, radius, self.wake_hour)

        def draw_energy_indicators():
            if self.show_energy.get() and self.wake_hour is not None:
                # Draw Visual Indicators (Bathyphase and Acrophase)
                if self.show_bathyphase.get() and self.bathyphase_hour is not None:
                    self._apply_energy_curve_params()
                    bathy_e = self.energy_curve.get_cached_energy(self.bathyphase_hour)
                    self.energy_curve.draw_bathyphase_indicator(center_x, center_y, radius, self.bathyphase_hour, bathy_e)

                if self.show_acrophase.get():
                    acro_h = self._predicted_acrophase_hour()
                    if acro_h is not None:
                        self.energy_curve.draw_acrophase_indicator(
                            center_x, center_y, radius, acro_h, self.energy_curve._cached_e_max
                        )

        def draw_perimeter_line():
            self.canvas.create_oval(
                center_x - radius, center_y - radius,
                center_x + radius, center_y + radius,
                outline="black", width=2
            )
           
        def draw_ticks_and_numbers():
            for h_tick in range(0, 24, 2):
                angle = (18 - h_tick) * 15
                rad = math.radians(angle)
               
                tick_len = radius * 0.12 if h_tick % 6 == 0 else radius * 0.08
               
                x1 = center_x + (radius - tick_len) * math.cos(rad)
                y1 = center_y - (radius - tick_len) * math.sin(rad)
                x2 = center_x + radius * math.cos(rad)
                y2 = center_y - radius * math.sin(rad)
               
                if self.sunrise_hour is not None and self.sunset_hour is not None:
                    if self.sunset_hour > self.sunrise_hour:
                        is_night = (h_tick >= self.sunset_hour) or (h_tick < self.sunrise_hour)
                    else:
                        is_night = (h_tick >= self.sunset_hour) and (h_tick < self.sunrise_hour)
                else:
                    is_night = False
                   
                tick_color = "white" if is_night else "black"
                text_color = "white" if is_night else "black"
               
                self.canvas.create_line(x1, y1, x2, y2, fill=tick_color, width=3 if h_tick % 6 == 0 else 2)
               
                if self.show_numbers.get() and h_tick % 2 == 0:
                    text_offset = radius * 0.25
                    tx = center_x + (radius - text_offset) * math.cos(rad)
                    ty = center_y - (radius - text_offset) * math.sin(rad)
                    font_size = max(8, int(radius / 9))
                   
                    display_num = h_tick % 12
                    if display_num == 0: display_num = 12
                   
                    self.canvas.create_text(tx, ty, text=str(display_num), font=("Segoe UI", font_size, "bold"), fill=text_color)
        
        def draw_calendar_events():
            if not self.show_calendar.get() or not self.calendar_events:
                return
            
            now_aware = datetime.datetime.now(datetime.timezone.utc)
            
            # 1. Identify current events and their maximum end time
            current_events = []
            max_current_end = now_aware
            for event in self.calendar_events:
                # Still skipping all-day events for the clock face as they'd clutter it
                if event.get('is_all_day'): continue
                s_dt = event['start_dt']
                e_dt = event['end_dt']
                if s_dt <= now_aware < e_dt:
                    current_events.append(event)
                    if e_dt > max_current_end:
                        max_current_end = e_dt
            
            # Show events occurring now or starting in the next 24 hours
            # Use a fixed 24h window from NOW for a consistent display
            window_end = now_aware + datetime.timedelta(hours=24)
            
            # 2. Filter and process events for display
            display_list = []
            for i, event in enumerate(self.calendar_events):
                if event.get('is_all_day'): continue
                s_dt = event['start_dt']
                e_dt = event['end_dt']
                
                is_current = any(e is event for e in current_events)
                
                # Include if it's currently occurring OR starts within the 24h window
                if is_current or (now_aware <= s_dt < window_end):
                    display_s_dt = s_dt
                    display_e_dt = e_dt
                    
                    # If it's currently occurring, start arc from the clock hand (now)
                    if is_current:
                        display_s_dt = now_aware
                    
                    # If it ends after our 24h window, truncate it for drawing
                    if e_dt > window_end:
                        display_e_dt = window_end
                    
                    local_s = display_s_dt.astimezone()
                    local_e = display_e_dt.astimezone()
                    
                    s_h = local_s.hour + local_s.minute / 60.0 + local_s.second / 3600.0
                    e_h = local_e.hour + local_e.minute / 60.0 + local_e.second / 3600.0
                    
                    orig_local_s = s_dt.astimezone()
                    orig_local_e = e_dt.astimezone()
                    
                    def format_time(dt):
                        return dt.strftime("%I:%M%p").lstrip("0").lower()
                        
                    time_range = f"{format_time(orig_local_s)} – {format_time(orig_local_e)}"
                    summary_text = event.get('summary', 'No Title')
                    full_text = f"{time_range}\n{summary_text}"
                    
                    display_list.append({
                        'summary': full_text,
                        's_h': s_h,
                        'e_h': e_h,
                        'is_current': is_current,
                        'original_index': i
                    })
            
            # 3. Sort so current events are last (drawn on top)
            display_list.sort(key=lambda x: (x['is_current'], x['s_h']))
            
            for event in display_list:
                s_h = event['s_h']
                e_h = event['e_h']
                
                duration = e_h - s_h
                if duration < 0: duration += 24
                
                # Don't draw tiny/zero duration events
                if duration < 0.05: continue
                
                start_angle = (18 - s_h) * 15
                extent = -(duration * 15)
                
                # Unique color from the 24-color set
                color = self.EVENT_COLORS[event['original_index'] % len(self.EVENT_COLORS)]
                
                # Draw further inward to avoid overlap with tick marks and sleep arcs
                margin = radius * 0.15
                r_arc = radius - margin
                
                width = max(6, int(radius/9))
                
                # Draw thin gray outline (slightly wider)
                self.canvas.create_arc(
                    center_x - r_arc, center_y - r_arc,
                    center_x + r_arc, center_y + r_arc,
                    start=start_angle, extent=extent,
                    outline="#444444", width=width + 2, style=tk.ARC,
                    tags="calendar_events"
                )
                
                # Draw colored event arc
                arc_id = self.canvas.create_arc(
                    center_x - r_arc, center_y - r_arc,
                    center_x + r_arc, center_y + r_arc,
                    start=start_angle, extent=extent,
                    outline=color, width=width, style=tk.ARC,
                    tags="calendar_events"
                )
                self._calendar_item_map[arc_id] = event.get('summary', 'No Title')
                   
        def draw_sun_and_moon():
            now = datetime.datetime.now()
            current_hour = now.hour + now.minute / 60.0 + now.second / 3600.0
            if self.show_sun_moon.get():
                # Astral/location data is optional. Keep the markers visible
                # with a deterministic clock-based fallback if it is missing
                # or temporarily unavailable during startup.
                try:
                    sun_rad, moon_rad, m_phase_val, sun_elev = self._get_celestial_positions(current_hour)
                except Exception as exc:
                    print(f"[ClockWidget] Celestial marker fallback: {exc}")
                    sun_rad, moon_rad, m_phase_val, sun_elev = (
                        self._fallback_celestial_positions(current_hour)
                    )
                self._draw_celestial_markers(
                    center_x, center_y, radius,
                    sun_rad, moon_rad, m_phase_val, sun_elev,
                )

        def draw_sleep_debt_text():
            if self.show_sleep_debt_text.get() and self.sleep_debt_hours is not None:
                midnight_angle = math.radians(-90)
                text_dist = radius * 0.5
                dx = center_x + text_dist * math.cos(midnight_angle)
                dy = center_y - text_dist * math.sin(midnight_angle)
                
                debt_int = int(round(self.sleep_debt_hours))
                self.canvas.create_text(
                    dx, dy,
                    text=f"{debt_int}h",
                    font=("Segoe UI", max(10, int(radius / 10)), "bold"),
                    fill="#EEEEEE" 
                )
                self.canvas.create_text(
                    dx, dy + max(12, int(radius / 8)),
                    text="Debt",
                    font=("Segoe UI", max(10, int(radius / 14)), "bold"),
                    fill="#EEEEEE" 
                )

        def draw_manual_wake_tick():
            if not self.show_manual_wake.get(): return
            val = self.manual_wake_time.get().strip()
            if not val: return
            try:
                parts = val.split(":")
                if len(parts) == 2:
                    h = int(parts[0])
                    m = int(parts[1])
                    if 0 <= h < 24 and 0 <= m < 60:
                        wake_hour = h + m / 60.0
                        angle = (18 - wake_hour) * 15
                        rad = math.radians(angle)
                        
                        tick_len = radius * 0.25
                        half_len = tick_len / 2
                        
                        # Calculate white tick points
                        x1 = center_x + (radius - half_len) * math.cos(rad)
                        y1 = center_y - (radius - half_len) * math.sin(rad)
                        x2 = center_x + (radius + half_len) * math.cos(rad)
                        y2 = center_y - (radius + half_len) * math.sin(rad)
                        
                        # Calculate black outline points (slightly longer to cover ends)
                        outline_offset = 1.5
                        bx1 = center_x + (radius - half_len - outline_offset) * math.cos(rad)
                        by1 = center_y - (radius - half_len - outline_offset) * math.sin(rad)
                        bx2 = center_x + (radius + half_len + outline_offset) * math.cos(rad)
                        by2 = center_y - (radius + half_len + outline_offset) * math.sin(rad)
                        
                        # Black outline
                        self.canvas.create_line(bx1, by1, bx2, by2, fill="black", width=7, capstyle=tk.BUTT)
                        # White tick mark
                        self.canvas.create_line(x1, y1, x2, y2, fill="white", width=4, capstyle=tk.BUTT)
            except ValueError:
                pass

        def draw_clock_hand():
            now = datetime.datetime.now()
            self._draw_clock_hand(now, center_x, center_y, radius)

        def draw_solar_circle():
            self._draw_solar_circle(center_x, center_y, radius)

        def draw_reauth_indicators():
            if not self.reauth_needed_fitbit and not self.reauth_needed_calendar:
                return
            
            # Draw a warning icon in the top-left of the clock face
            icon_r = radius * 0.15
            margin = radius * 0.2
            ix = center_x - radius + margin
            iy = center_y - radius + margin
            
            # Fitbit warning (blue-ish)
            if self.reauth_needed_fitbit:
                fid = self.canvas.create_oval(
                    ix - icon_r, iy - icon_r, ix + icon_r, iy + icon_r,
                    fill="#00ccff", outline="white", width=2, tags="reauth_icon_fitbit"
                )
                self.canvas.create_text(ix, iy, text="F", fill="white", font=("Arial", int(icon_r), "bold"), tags="reauth_icon_fitbit")
                self.canvas.tag_bind("reauth_icon_fitbit", "<Button-1>", lambda e: self.relink_fitbit_and_refresh())
                Tooltip(self.canvas, "Fitbit Auth Expired - Click to Re-link", tag="reauth_icon_fitbit")
                iy += icon_r * 2.5
                
            # Calendar warning (purple-ish)
            if self.reauth_needed_calendar:
                cid = self.canvas.create_oval(
                    ix - icon_r, iy - icon_r, ix + icon_r, iy + icon_r,
                    fill="#9370DB", outline="white", width=2, tags="reauth_icon_cal"
                )
                self.canvas.create_text(ix, iy, text="C", fill="white", font=("Arial", int(icon_r), "bold"), tags="reauth_icon_cal")
                # Trigger Google Calendar auth
                def trigger_cal_auth(e):
                    # Reset service to force re-auth flow
                    self.calendar_svc = None
                    self.update_calendar_data()
                self.canvas.tag_bind("reauth_icon_cal", "<Button-1>", trigger_cal_auth)
                Tooltip(self.canvas, "Calendar Auth Expired - Click to Re-link", tag="reauth_icon_cal")

        # Map string names to their drawing functions
        layers = {
            "background_face": draw_background_face,
            "night_shading": draw_night_shading,
            "calendar_events": draw_calendar_events,
            "sleep_arc": draw_sleep_arc,
            "energy_curve": draw_energy_curve,
            "perimeter_line": draw_perimeter_line,
            "ticks_and_numbers": draw_ticks_and_numbers,
            "sun_and_moon": draw_sun_and_moon,
            "manual_wake_tick": draw_manual_wake_tick,
            "solar_circle": draw_solar_circle,
            "sleep_debt_text": draw_sleep_debt_text,
            "reauth_indicators": draw_reauth_indicators,
            "energy_indicators": draw_energy_indicators,
            "clock_hand": draw_clock_hand
        }

        # Draw only invalidated layers and tag every newly created item. Existing
        # hand/solar/energy objects are updated in place by their draw methods.
        for layer_name in self.DRAW_ORDER:
            if layer_name in layers and layer_name in requested_layers:
                before = set(self.canvas.find_all())
                try:
                    layers[layer_name]()
                except Exception as exc:
                    # A malformed remote event or model value must not abort
                    # the remainder of the compositor after the canvas was
                    # cleared. Keep later layers (including the hand) alive.
                    print(f"[ClockWidget] draw layer '{layer_name}' failed: {exc}")
                    if layer_name == "solar_circle":
                        circle_r = radius * (2 / 13)
                        self.canvas.create_oval(
                            center_x - circle_r, center_y - circle_r,
                            center_x + circle_r, center_y + circle_r,
                            fill="#000000", outline="", tags="solar_circle",
                        )
                    elif layer_name == "clock_hand":
                        current_hour = datetime.datetime.now().hour
                        current_hour += datetime.datetime.now().minute / 60.0
                        hand_rad = math.radians((18 - current_hour) * 15)
                        hx = center_x + radius * math.cos(hand_rad)
                        hy = center_y - radius * math.sin(hand_rad)
                        self.canvas.create_line(
                            center_x, center_y, hx, hy,
                            fill="#FF9F1C", width=max(2, int(radius / 25)),
                            arrow=tk.LAST, tags="clock_hand",
                        )
                        dot_r = max(2, int(radius / 50))
                        self.canvas.create_oval(
                            center_x - dot_r, center_y - dot_r,
                            center_x + dot_r, center_y + dot_r,
                            fill="#FF9F1C", outline="", tags="clock_hand",
                        )
                new_items = set(self.canvas.find_all()) - before
                for item_id in new_items:
                    self.canvas.addtag_withtag(f"layer:{layer_name}", item_id)

        # Full redraws create items in DRAW_ORDER, so the canvas already has
        # the correct stacking. Avoid raising tagged groups: on some Tk builds
        # a colon-containing tag can be treated as an item identifier and move
        # the face above all overlays.
        if self._tooltip_bg_id and self._tooltip_id:
            self.canvas.tag_lower(self._tooltip_bg_id, self._tooltip_id)
        # Controls are deliberately drawn last so they remain above the face,
        # hand, and data overlays while the clock is solid.
        if not self.is_clock_transparent:
            self._draw_hover_controls()
        if full_redraw:
            self._has_full_clock_draw = True

    def save_clock_image(self):
        """Capture plain state and render the archive image off the Tk thread."""
        if self.wake_hour is None:
            return
        self._apply_energy_curve_params()
        snapshot = {
            'wake_hour': self.wake_hour,
            'sleep_hour': self.sleep_hour,
            'sleep_duration': self.sleep_duration,
            'show_total_bedtime': self.show_total_bedtime.get(),
            'sunrise_hour': self.sunrise_hour,
            'sunset_hour': self.sunset_hour,
            'energy': {
                name: getattr(self.energy_curve, name)
                for name in (
                    'wake_hour', 'sleep_debt_hours', 'sleep_duration', 'bathyphase_hour',
                    'nap_intervals', 'tau_wake', 'tau_sleep', 'tau_inertia',
                    'debt_factor', 'circadian_peak_offset',
                )
            },
            'timestamp': datetime.datetime.now(),
        }
        self._submit_background(lambda: self._render_clock_image(snapshot))

    def _render_clock_image(self, snapshot):
        from PIL import Image, ImageDraw

        folder = os.path.join(os.path.dirname(__file__), "saved_curves")
        os.makedirs(folder, exist_ok=True)
        timestamp = snapshot['timestamp'].strftime("%Y-%m-%d_%H-%M-%S")
        filepath = os.path.join(folder, f"{timestamp}_energy_curve.png")

        size = 1200
        img = Image.new('RGB', (size, size), "#2b2b2b")
        draw = ImageDraw.Draw(img)
        center_x = size / 2
        center_y = size / 2
        scale = size / 230.0
        radius = 77 * scale
        draw.ellipse([center_x - radius, center_y - radius, center_x + radius, center_y + radius], fill="#ffffff", outline="black", width=int(3 * scale))

        sunrise_hour = snapshot['sunrise_hour']
        sunset_hour = snapshot['sunset_hour']
        if sunrise_hour is not None and sunset_hour is not None:
            night_hours = sunrise_hour - sunset_hour
            if night_hours < 0: night_hours += 24
            sunset_angle_tk = (18 - sunset_hour) * 15
            extent_tk = -(night_hours * 15)
            pil_start = -sunset_angle_tk
            pil_end = pil_start - extent_tk
            draw.pieslice([center_x - radius, center_y - radius, center_x + radius, center_y + radius], start=pil_start, end=pil_end, fill="#2C3E50")

        wake_hour = snapshot['wake_hour']
        if wake_hour is not None:
            if snapshot['show_total_bedtime']:
                start_h = snapshot['sleep_hour']
            else:
                duration = snapshot['sleep_duration']
                start_h = (wake_hour - duration) % 24 if duration else snapshot['sleep_hour']
            if start_h is not None:
                display_dur = wake_hour - start_h
                if display_dur < 0: display_dur += 24
                sleep_start_angle = (18 - start_h) * 15
                sleep_extent = -(display_dur * 15)
                margin = radius * 0.15
                bbox_sleep = [center_x - (radius - margin), center_y - (radius - margin),
                              center_x + (radius - margin), center_y + (radius - margin)]
                draw.pieslice(bbox_sleep, start=-sleep_start_angle, end=-sleep_start_angle - sleep_extent, fill="#6A5ACD")

        curve = EnergyCurve(None)
        for name, value in snapshot['energy'].items():
            setattr(curve, name, value)
        curve.normalize = True
        curve.draw(center_x, center_y, radius, wake_hour, draw_obj=draw, width_scale=scale)

        draw.ellipse([center_x - radius, center_y - radius, center_x + radius, center_y + radius], outline="black", width=int(2 * scale))
        for h_tick in range(0, 24, 2):
            angle = (18 - h_tick) * 15
            rad = math.radians(angle)
            tick_len = radius * 0.12 if h_tick % 6 == 0 else radius * 0.08
            x1 = center_x + (radius - tick_len) * math.cos(rad)
            y1 = center_y - (radius - tick_len) * math.sin(rad)
            x2 = center_x + radius * math.cos(rad)
            y2 = center_y - radius * math.sin(rad)
            
            if sunrise_hour is not None and sunset_hour is not None:
                is_night = (h_tick >= sunset_hour or h_tick < sunrise_hour) if sunset_hour > sunrise_hour else (h_tick >= sunset_hour and h_tick < sunrise_hour)
            else:
                is_night = False
            tick_color = "white" if is_night else "black"
            draw.line([(x1, y1), (x2, y2)], fill=tick_color, width=int(3 * scale if h_tick % 6 == 0 else 2 * scale))

        img.save(filepath)
        print(f"[save_clock_image] Saved energy snapshot to: {filepath}")
        self._post_ui(self._mark_today_image_saved, snapshot['timestamp'].strftime("%Y-%m-%d"))

    def _mark_today_image_saved(self, date_str):
        self._today_image_cache = (date_str, True)

    def _has_today_image(self):
        """Returns True if at least one energy curve image exists for today."""
        folder = os.path.join(os.path.dirname(__file__), "saved_curves")
        date_str = datetime.datetime.now().strftime("%Y-%m-%d")
        cached = getattr(self, '_today_image_cache', None)
        if cached and cached[0] == date_str:
            return cached[1]
        if not os.path.exists(folder):
            self._today_image_cache = (date_str, False)
            return False
        found = False
        try:
            for f in os.listdir(folder):
                if f.startswith(date_str):
                    found = True
                    break
        except:
            pass
        self._today_image_cache = (date_str, found)
        return found



if __name__ == "__main__":
    root = tk.Tk()
    app = ClockWidget(root)
    root.mainloop()
