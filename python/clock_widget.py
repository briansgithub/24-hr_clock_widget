import tkinter as tk
import os
from tkinter import ttk
import math
import datetime
import threading
import urllib.request
import json
from astral import Observer
from astral.sun import sun, elevation as sun_elevation
from astral.moon import phase, elevation as moon_elevation
from PIL import Image, ImageDraw, ImageTk, ImageFont
import pystray
import pystray
from pystray import MenuItem as item
from fitbit_client import FitbitClient, ReauthRequiredError
from google_calendar_client import get_calendar_service, get_calendar_events, ReauthRequiredError as CalReauthRequiredError
from energy_logic import EnergyCurve, get_energy_level

class Tooltip:
    def __init__(self, widget, text):
        self.widget = widget
        self.text = text
        self.tip_window = None
        self.widget.bind("<Enter>", self.show_tip)
        self.widget.bind("<Leave>", self.hide_tip)

    def show_tip(self, event=None):
        if self.tip_window or not self.text:
            return
        x = self.widget.winfo_rootx() + 20
        y = self.widget.winfo_rooty() + self.widget.winfo_height() + 5
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

        self.sleep_settings_file = os.path.join(os.path.dirname(__file__), "sleep_settings.json")
        self.excluded_dates = self._load_sleep_settings()

        # Load Fitbit Config
        self._load_fitbit_config()

        # Model parameters will redraw on slider release (handled in _add_slider)
        # except for boolean toggles which still trace or use command
        self.use_bathyphase.trace_add("write", lambda *args: getattr(self, 'canvas', None) and self.draw_clock())
        self.show_bathyphase.trace_add("write", lambda *args: getattr(self, 'canvas', None) and (self.draw_clock(), self._save_sleep_settings()))
        self.show_acrophase.trace_add("write", lambda *args: getattr(self, 'canvas', None) and (self.draw_clock(), self._save_sleep_settings()))
        self.manual_wake_time.trace_add("write", lambda *args: getattr(self, 'canvas', None) and (self.draw_clock(), self._save_sleep_settings()))

        # ── Fitbit Integration ────────────────────────────────────────────────
        self.fitbit = FitbitClient(
            client_id=self.FITBIT_CLIENT_ID,
            client_secret=self.FITBIT_CLIENT_SECRET
        )
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
        
        # Tooltip state
        self._calendar_item_map = {}
        self._tooltip_id = None
        self._tooltip_bg_id = None
       
        # Main Frame
        self.main_frame = tk.Frame(self.root, bg=self.solid_bg)
        self.main_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=0)
       
        # UI Elements
        self.canvas = tk.Canvas(self.main_frame, bg=self.solid_bg, highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)
       
        self.controls_window = tk.Toplevel(self.root)
        self.controls_window.title("Controls")
        self.controls_window.configure(bg=self.solid_bg)
        self.controls_window.overrideredirect(True)
        self.controls_window.withdraw()

        # ── Sleep Log Window ──────────────────────────────────────────────────
        self.sleep_table_window = tk.Toplevel(self.root)
        self.sleep_table_window.title("Sleep Log")
        self.sleep_table_window.configure(bg=self.solid_bg)
        self.sleep_table_window.overrideredirect(True)
        self.sleep_table_window.withdraw()
        self._build_sleep_table()

        # ── Sleep Model Window ────────────────────────────────────────────────
        self.sleep_model_window = tk.Toplevel(self.root)
        self.sleep_model_window.title("Sleep Model")
        self.sleep_model_window.configure(bg=self.solid_bg)
        self.sleep_model_window.overrideredirect(True)
        self.sleep_model_window.withdraw()
        self._build_sleep_model()

        self.controls_frame = tk.Frame(self.controls_window, bg=self.solid_bg)
        self.controls_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
       
        self._control_widgets = []
        self.inner_controls = tk.Frame(self.controls_frame, bg=self.solid_bg)
        self.inner_controls.pack(anchor=tk.CENTER)
        
        self.left_col = tk.Frame(self.inner_controls, bg=self.solid_bg)
        self.left_col.pack(side=tk.LEFT, anchor=tk.N, padx=(0, 10))
        
        self.right_col = tk.Frame(self.inner_controls, bg=self.solid_bg)
        self.right_col.pack(side=tk.LEFT, anchor=tk.N, padx=(10, 0))

        add_divider = self._add_divider
        add_toggle = self._add_toggle

        def on_toggle_change():
            self.draw_clock()
            self._save_sleep_settings()

        add_divider("SETTINGS", self.left_col)
        settings_frame = tk.Frame(self.left_col, bg=self.solid_bg)
        settings_frame.pack(side=tk.TOP, fill=tk.X, pady=2)
        
        tk.Label(settings_frame, text="Hover Delay (s):", bg=self.solid_bg, fg="white", font=("Arial", 8)).pack(side=tk.LEFT)
        
        self.hover_delay_var = tk.StringVar(value="0.2")
        
        def validate_num(P):
            if P == "": return True
            try:
                if P == ".": return True
                float(P)
                return True
            except ValueError:
                return False
        
        vcmd = (self.root.register(validate_num), '%P')
        
        self.hover_delay_entry = tk.Entry(
            settings_frame, 
            textvariable=self.hover_delay_var, 
            width=5, 
            validate='key', 
            validatecommand=vcmd,
            bg="#404040",
            fg="white",
            insertbackground="white",
            relief=tk.FLAT
        )
        self.hover_delay_entry.pack(side=tk.LEFT, padx=5)

        tk.Label(settings_frame, text="Timeout (s):", bg=self.solid_bg, fg="white", font=("Arial", 8)).pack(side=tk.LEFT, padx=(5, 0))
        
        self.timeout_delay_var = tk.StringVar(value="0.3")
        
        self.timeout_delay_entry = tk.Entry(
            settings_frame, 
            textvariable=self.timeout_delay_var, 
            width=5, 
            validate='key', 
            validatecommand=vcmd,
            bg="#404040",
            fg="white",
            insertbackground="white",
            relief=tk.FLAT
        )
        self.timeout_delay_entry.pack(side=tk.LEFT, padx=5)


        self.top_toggle = add_toggle("Always On Top", self.always_on_top, self.toggle_topmost, self.left_col)

        add_divider("DISPLAY", self.left_col)
        wake_frame = tk.Frame(self.left_col, bg=self.solid_bg)
        wake_frame.pack(side=tk.TOP, fill=tk.X, pady=2)
        
        self.manual_wake_toggle = tk.Checkbutton(
            wake_frame,
            text="Wake up (HH:mm):",
            variable=self.show_manual_wake,
            command=on_toggle_change,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.manual_wake_toggle.pack(side=tk.LEFT)
        self._control_widgets.append(self.manual_wake_toggle)

        self.manual_wake_entry = tk.Entry(
            wake_frame, 
            textvariable=self.manual_wake_time, 
            width=6, 
            bg="#404040",
            fg="white",
            insertbackground="white",
            relief=tk.FLAT
        )
        self.manual_wake_entry.pack(side=tk.LEFT, padx=5)

        self.numbers_toggle = add_toggle("Numbers", self.show_numbers, on_toggle_change, self.left_col)
        self.sun_moon_toggle = add_toggle("Sun & Moon Icons", self.show_sun_moon, on_toggle_change, self.left_col)

        add_divider("METRICS", self.right_col)
        metrics_frame = tk.Frame(self.right_col, bg=self.solid_bg)
        metrics_frame.pack(side=tk.TOP, fill=tk.X, pady=2)
        
        self.bathyphase_label = tk.Label(metrics_frame, text="Bathyphase: --:-- --", bg=self.solid_bg, fg="white", font=("Arial", 8, "bold"))
        self.bathyphase_label.pack(side=tk.TOP, anchor=tk.W)
        
        self.efficiency_label = tk.Label(metrics_frame, text="Sleep Efficiency: --.-%", bg=self.solid_bg, fg="white", font=("Arial", 8, "bold"))
        self.efficiency_label.pack(side=tk.TOP, anchor=tk.W)


        add_divider("SLEEP", self.right_col)
        self.sleep_toggle = add_toggle("Show Sleep on Clock", self.show_sleep, on_toggle_change, self.right_col)
        self.bedtime_toggle = add_toggle("Time in Bed vs. Asleep Hrs.", self.show_total_bedtime, on_toggle_change, self.right_col)
        self.debt_text_toggle = add_toggle("Show Sleep Debt Text", self.show_sleep_debt_text, on_toggle_change, self.right_col)
        self.table_toggle = add_toggle("Show Sleep Log Table", self.show_sleep_table, self.update_sleep_table_visibility, self.right_col)

        add_divider("ENERGY", self.right_col)
        self.energy_toggle = add_toggle("Show Energy Curve", self.show_energy, on_toggle_change, self.right_col)
        self.energy_pct_toggle = add_toggle("Show Energy %", self.show_energy_pct, on_toggle_change, self.right_col)
        self.normalize_toggle = add_toggle("Normalize Energy", self.normalize_energy, on_toggle_change, self.right_col)
        self.debt_toggle = add_toggle("Factor in Sleep Debt", self.show_sleep_debt, on_toggle_change, self.right_col)
        self.naps_toggle = add_toggle("Include Naps", self.include_naps, self.update_fitbit_data, self.right_col)
        self.model_win_toggle = add_toggle("Advanced Model Settings", self.show_sleep_model, self.update_sleep_model_visibility, self.right_col)
        
        self.refresh_btn = tk.Button(
            self.right_col,
            text="Fitbit API Refresh",
            command=lambda: self.update_fitbit_data(force=True),
            bg="#404040",
            fg="#00ffcc",
            activebackground="#555555",
            activeforeground="white",
            relief=tk.FLAT,
            padx=10,
            pady=2,
            font=("Arial", 8, "bold")
        )
        self.refresh_btn.pack(side=tk.TOP, anchor=tk.W, pady=(10, 0), padx=(20, 0))
        self._control_widgets.append(self.refresh_btn)
        

        
        add_divider("CALENDAR", self.left_col)
        self.calendar_toggle = add_toggle("Show Calendar Events", self.show_calendar, self.draw_clock, self.left_col)
        
        self.calendar_refresh_btn = tk.Button(
            self.left_col,
            text="Calendar API Refresh",
            command=self.update_calendar_data,
            bg="#404040",
            fg="#9370DB", 
            activebackground="#555555",
            activeforeground="white",
            relief=tk.FLAT,
            padx=10,
            pady=2,
            font=("Arial", 8, "bold")
        )
        self.calendar_refresh_btn.pack(side=tk.TOP, anchor=tk.W, pady=(5, 0), padx=(20, 0))
        self._control_widgets.append(self.calendar_refresh_btn)
        


        self.sunrise_hour = None
        self.sunset_hour = None
       
        self.canvas.bind("<Configure>", self.on_resize)
       
        # Bind hover events
        for widget in (self.root, self.controls_window, self.sleep_table_window, self.sleep_model_window):
            widget.bind("<Enter>", self.on_enter)
            widget.bind("<Leave>", self.on_leave)
       
        # Bind drag events
        for widget in (self.main_frame, self.canvas):
            widget.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.root))
            widget.bind("<B1-Motion>", lambda e: self.on_drag_motion(e))
            widget.bind("<ButtonPress-3>", self.on_right_click_start)
            widget.bind("<B3-Motion>", self.on_right_click_motion)
            widget.bind("<ButtonRelease-3>", self.on_right_click_end)
        
        self.canvas.bind("<Motion>", self.on_canvas_motion)

        for widget in (self.controls_frame, self.inner_controls):
            widget.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.controls_window))
            widget.bind("<B1-Motion>", lambda e: self.on_drag_motion(e))
       
        # Initialize Energy Curve logic
        self.energy_curve = EnergyCurve(self.canvas)

        # Start hidden and wait for sun times
        self.root.withdraw()
        self.fetch_sun_times()
        self.update_clock()

    def toggle_topmost(self):
        self.root.attributes('-topmost', self.always_on_top.get())
        if hasattr(self, 'controls_window'):
            self.controls_window.attributes('-topmost', self.always_on_top.get())
        if hasattr(self, 'sleep_model_window'):
            self.sleep_model_window.attributes('-topmost', self.always_on_top.get())


    def get_borders(self):
        if hasattr(self, '_bx'): return self._bx, self._by
        try:
            import ctypes
            user32 = ctypes.windll.user32
            pad = user32.GetSystemMetrics(92)
            self._bx = user32.GetSystemMetrics(32) + pad
            self._by = user32.GetSystemMetrics(33) + pad + user32.GetSystemMetrics(4)
        except Exception:
            self._bx, self._by = 8, 31
        return self._bx, self._by

    def make_clock_solid(self):
        if not self.is_clock_transparent: return
        self.is_clock_transparent = False
        
        bg_color = self.solid_bg
        self.root.configure(bg=bg_color)
        self.main_frame.configure(bg=bg_color)
        self.canvas.configure(bg=bg_color)
        
        rx = self.root.winfo_rootx()
        ry = self.root.winfo_rooty()
        bx, by = self.get_borders()
        
        if self.root.winfo_viewable():
            self.root.geometry(f"+{rx - bx}+{ry - by}")
        self.root.overrideredirect(False)
        self.root.attributes('-topmost', self.always_on_top.get())

    def make_clock_transparent(self):
        if self.is_clock_transparent: return
        self.is_clock_transparent = True
        
        bg_color = self.transparent_key
        self.root.configure(bg=bg_color)
        self.main_frame.configure(bg=bg_color)
        self.canvas.configure(bg=bg_color)
        
        rx = self.root.winfo_rootx()
        ry = self.root.winfo_rooty()
        
        if self.root.winfo_viewable():
            self.root.geometry(f"+{rx}+{ry}")
        self.root.overrideredirect(True)
        self.root.attributes('-topmost', self.always_on_top.get())

    def make_controls_visible(self):
        if not hasattr(self, 'controls_window'): return
        if getattr(self, 'is_controls_visible', False): return
        self.is_controls_visible = True
        
        if not getattr(self, '_controls_detached', False):
            rx = self.root.winfo_rootx()
            ry = self.root.winfo_rooty()
            rw = self.root.winfo_width()
            self.controls_window.geometry(f"+{rx + rw + 10}+{ry}")
            
        self.controls_window.deiconify()
        self.controls_window.lift()
        self.controls_window.attributes('-topmost', self.always_on_top.get())

        # Position windows to the right of controls
        if getattr(self, 'is_controls_visible', False):
            self.update_sleep_table_visibility()
            self.update_sleep_model_visibility()
        else:
            if hasattr(self, 'sleep_table_window'):
                self.sleep_table_window.withdraw()
            if hasattr(self, 'sleep_model_window'):
                self.sleep_model_window.withdraw()

    def make_controls_hidden(self):
        if not hasattr(self, 'controls_window'): return
        if not getattr(self, 'is_controls_visible', False): return
        self.is_controls_visible = False
        self.controls_window.withdraw()
        if hasattr(self, 'sleep_table_window'):
            self.sleep_table_window.withdraw()
        if hasattr(self, 'sleep_model_window'):
            self.sleep_model_window.withdraw()

    def update_sleep_table_visibility(self):
        """Explicitly show/hide and position the sleep table based on its toggle variable."""
        if not hasattr(self, 'sleep_table_window'): return
        
        if self.show_sleep_table.get() and getattr(self, 'is_controls_visible', False):
            # Position and show
            self.controls_window.update_idletasks()
            cx = self.controls_window.winfo_rootx()
            cy = self.controls_window.winfo_rooty()
            cw = self.controls_window.winfo_width()
            self.sleep_table_window.geometry(f"+{cx + cw + 10}+{cy}")
            self.sleep_table_window.deiconify()
            self.sleep_table_window.lift()
            self.sleep_table_window.attributes('-topmost', self.always_on_top.get())
            if self._sleep_table_needs_refresh and not self._sleep_table_refresh_scheduled:
                self._sleep_table_refresh_scheduled = True
                self.sleep_table_window.after_idle(self._refresh_sleep_table_if_visible)
            # If sleep model is also shown, it must move further right
            if self.show_sleep_model.get():
                self.update_sleep_model_visibility()
        else:
            self.sleep_table_window.withdraw()
            # Re-position sleep model if it's shown
            if self.show_sleep_model.get() and getattr(self, 'is_controls_visible', False):
                self.update_sleep_model_visibility()

    def _refresh_sleep_table_if_visible(self):
        self._sleep_table_refresh_scheduled = False
        if not self.show_sleep_table.get() or not getattr(self, 'is_controls_visible', False):
            return
        if not hasattr(self, 'sleep_table_window') or not self.sleep_table_window.winfo_viewable():
            return
        self.populate_sleep_table()

    def update_sleep_model_visibility(self):
        """Explicitly show/hide and position the sleep model based on its toggle variable."""
        if not hasattr(self, 'sleep_model_window'): return
        
        if self.show_sleep_model.get() and getattr(self, 'is_controls_visible', False):
            # Position and show
            self.controls_window.update_idletasks()
            cx = self.controls_window.winfo_rootx()
            cy = self.controls_window.winfo_rooty()
            cw = self.controls_window.winfo_width()
            
            target_x = cx + cw + 10
            
            # If sleep table is ALSO visible, move further right
            if self.show_sleep_table.get() and self.sleep_table_window.winfo_viewable():
                self.sleep_table_window.update_idletasks()
                tw = self.sleep_table_window.winfo_width()
                target_x += tw + 10
                
            self.sleep_model_window.geometry(f"+{target_x}+{cy}")
            self.sleep_model_window.deiconify()
            self.sleep_model_window.lift()
            self.sleep_model_window.attributes('-topmost', self.always_on_top.get())
        else:
            self.sleep_model_window.withdraw()

    def make_solid(self):
        self.make_clock_solid()
        self.make_controls_visible()
        
        # Trigger an opportunistic background refresh if data is stale (> 5 min)
        now = datetime.datetime.now()
        
        # 1. Fitbit refresh
        last_fitbit = getattr(self, 'last_fitbit_update', None)
        if last_fitbit is None or (now - last_fitbit).total_seconds() > 300:
            print("[ClockWidget] Opportunistic hover refresh: Fitbit.")
            self.update_fitbit_data()

        # 2. Calendar refresh
        last_cal = getattr(self, '_last_calendar_refresh_time', None)
        if last_cal is None or (now - last_cal).total_seconds() > 300:
            print("[ClockWidget] Opportunistic hover refresh: Calendar.")
            self.update_calendar_data()
            
        if not getattr(self, '_checking_nearby', False):
            self._checking_nearby = True
            self.check_nearby()

    def make_transparent(self):
        self._checking_nearby = False
        if getattr(self, '_clock_hide_timer_started', False):
            self.root.after_cancel(self._clock_hide_timer_id)
            self._clock_hide_timer_started = False
        if getattr(self, '_controls_hide_timer_started', False):
            self.root.after_cancel(self._controls_hide_timer_id)
            self._controls_hide_timer_started = False
        self.make_clock_transparent()
        self.make_controls_hidden()

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
       
        rx = self.root.winfo_rootx()
        ry = self.root.winfo_rooty()
        rw = self.root.winfo_width()
        rh = self.root.winfo_height()
       
        margin = 10
        root_margin = 20 # More generous margin for resize handles
        in_root = not (mx < rx - root_margin or mx > rx + rw + root_margin or my < ry - root_margin or my > ry + rh + root_margin)
        
        in_controls = False
        if getattr(self, 'controls_window', None) and self.controls_window.winfo_viewable():
            cx = self.controls_window.winfo_rootx()
            cy = self.controls_window.winfo_rooty()
            cw = self.controls_window.winfo_width()
            ch = self.controls_window.winfo_height()
            in_controls = not (mx < cx - margin or mx > cx + cw + margin or my < cy - margin or my > cy + ch + margin)

        if not in_controls and getattr(self, 'sleep_table_window', None) and self.sleep_table_window.winfo_viewable():
            tx = self.sleep_table_window.winfo_rootx()
            ty = self.sleep_table_window.winfo_rooty()
            tw = self.sleep_table_window.winfo_width()
            th = self.sleep_table_window.winfo_height()
            in_controls = not (mx < tx - margin or mx > tx + tw + margin or my < ty - margin or my > ty + th + margin)

        if not in_controls and getattr(self, 'sleep_model_window', None) and self.sleep_model_window.winfo_viewable():
            sx = self.sleep_model_window.winfo_rootx()
            sy = self.sleep_model_window.winfo_rooty()
            sw = self.sleep_model_window.winfo_width()
            sh = self.sleep_model_window.winfo_height()
            in_controls = not (mx < sx - margin or mx > sx + sw + margin or my < sy - margin or my > sy + sh + margin)

        if in_root and in_controls:
            top_widget = self.root.winfo_containing(mx, my)
            if top_widget is not None:
                top_win = top_widget.winfo_toplevel()
                if top_win == self.root:
                    in_controls = False
                elif top_win == getattr(self, 'controls_window', None):
                    in_root = False
                elif getattr(self, 'sleep_table_window', None) and top_win == self.sleep_table_window:
                    in_root = False
                elif getattr(self, 'sleep_model_window', None) and top_win == self.sleep_model_window:
                    in_root = False
            else:
                # If on border (None), prioritize root if we are in its generous margin
                # and it's currently solid (to allow resizing)
                if in_root and not getattr(self, 'is_clock_transparent', False):
                    in_controls = False

        def get_timeout_ms():
            try:
                return int(float(self.timeout_delay_var.get()) * 1000)
            except:
                return 1000

        # Clock Timeout Logic
        if in_controls:
            if getattr(self, '_clock_hide_timer_started', False):
                self.root.after_cancel(self._clock_hide_timer_id)
                self._clock_hide_timer_started = False
            self.make_clock_transparent()
        elif not in_root:
            if not getattr(self, '_clock_hide_timer_started', False):
                self._clock_hide_timer_started = True
                self._clock_hide_timer_id = self.root.after(get_timeout_ms(), self.make_clock_transparent)
        else:
            if getattr(self, '_clock_hide_timer_started', False):
                self.root.after_cancel(self._clock_hide_timer_id)
                self._clock_hide_timer_started = False
            self.make_clock_solid()

        # Controls Timeout Logic
        if not in_controls and not in_root:
            if not getattr(self, '_controls_hide_timer_started', False):
                self._controls_hide_timer_started = True
                self._controls_hide_timer_id = self.root.after(get_timeout_ms(), self.make_controls_hidden)
        else:
            if getattr(self, '_controls_hide_timer_started', False):
                self.root.after_cancel(self._controls_hide_timer_id)
                self._controls_hide_timer_started = False
            self.make_controls_visible()

        # Stop polling only if both are hidden
        if getattr(self, 'is_clock_transparent', False) and not getattr(self, 'is_controls_visible', False):
            self._checking_nearby = False
            return

        self.root.after(250, self.check_nearby)

    def on_enter(self, event):
        if self.hover_timer is not None:
            self.root.after_cancel(self.hover_timer)
            self.hover_timer = None
            
        toplevel = event.widget.winfo_toplevel()
        if toplevel == self.root:
            if getattr(self, 'is_clock_transparent', False):
                try:
                    delay = int(float(self.hover_delay_var.get()) * 1000)
                except:
                    delay = 750
                self.hover_timer = self.root.after(delay, self.make_solid)
            else:
                if not getattr(self, '_checking_nearby', False):
                    self._checking_nearby = True
                    self.check_nearby()
        else:
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

                    return data.get("excluded_dates", [today_str])
            except:
                pass
        return [today_str]

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
                    "show_acrophase": self.show_acrophase.get()
                }
            }
            with open(self.sleep_settings_file, 'w') as f:
                json.dump(data, f)
        except:
            pass

    def on_drag_start(self, event, target_window=None):
        if target_window is None:
            target_window = self.root
            
        # Don't start a window drag if clicking on interactive widgets
        if isinstance(event.widget, (tk.Scale, tk.Checkbutton, tk.Button, tk.Entry)):
            return
            
        if target_window == self.root and getattr(self, 'is_clock_transparent', False):
            self._drag_data = None
            return
           
        w = target_window.winfo_width()
        h = target_window.winfo_height()
       
        rx = event.x_root - target_window.winfo_rootx()
        ry = event.y_root - target_window.winfo_rooty()
       
        if target_window == self.root:
            margin = 20
            if rx < margin or rx > w - margin or ry < margin or ry > h - margin:
                self._drag_data = None
                return
        else:
            self._controls_detached = True
           
        self._drag_data = {'x': event.x_root, 'y': event.y_root,
                           'wx': target_window.winfo_x(), 'wy': target_window.winfo_y(),
                           'window': target_window}

    def on_drag_motion(self, event):
        if getattr(self, '_drag_data', None):
            target_window = self._drag_data['window']
            dx = event.x_root - self._drag_data['x']
            dy = event.y_root - self._drag_data['y']
            new_x = self._drag_data['wx'] + dx
            new_y = self._drag_data['wy'] + dy
            target_window.geometry(f"+{new_x}+{new_y}")
            
            if target_window == self.root and hasattr(self, 'controls_window') and self.controls_window.winfo_viewable():
                if not getattr(self, '_controls_detached', False):
                    rw = self.root.winfo_width()
                    self.controls_window.geometry(f"+{new_x + rw + 10}+{new_y}")

    def on_leave(self, event):
        if self.hover_timer is not None:
            self.root.after_cancel(self.hover_timer)
            self.hover_timer = None

    def _get_max_energy(self):
        current_args = (
            self.wake_hour, self.bathyphase_hour,
            self.tau_wake.get(), self.tau_sleep.get(), self.tau_inertia.get(), 
            self.debt_factor.get(), self.circadian_offset.get(), self.use_bathyphase.get()
        )
        if not hasattr(self, '_max_rested_energy') or getattr(self, '_max_rested_energy_args', None) != current_args:
            max_e = 0
            for i in range(1440):
                h_test = (i / 1440.0) * 24.0
                e = get_energy_level(
                    h_test,
                    self.wake_hour,
                    0.0,
                    self.bedtime_goal.get(),
                    self.bathyphase_hour if self.use_bathyphase.get() else None,
                    clamp=False,
                    tau_wake=self.tau_wake.get(),
                    tau_sleep=self.tau_sleep.get(),
                    tau_inertia=self.tau_inertia.get(),
                    debt_factor=self.debt_factor.get(),
                    circadian_peak_offset=None if self.use_bathyphase.get() else self.circadian_offset.get()
                )
                if e > max_e:
                    max_e = e
            self._max_rested_energy = max_e
            self._max_rested_energy_args = current_args
        return self._max_rested_energy

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
            max_e = self._get_max_energy()
            if max_e > 0:
                pct = max(0, int(round((current_e / max_e) * 100)))
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
                self.lat = lat
                self.lon = lon
                obs = Observer(latitude=lat, longitude=lon)
                # Pass the local timezone to astral to ensure sun events are found for 'today'
                local_tz = datetime.datetime.now().astimezone().tzinfo
                s = sun(obs, date=datetime.date.today(), tzinfo=local_tz)
               
                sunrise_dt = s['sunrise']
                sunset_dt = s['sunset']
               
                self.sunrise_hour = sunrise_dt.hour + sunrise_dt.minute / 60.0 + sunrise_dt.second / 3600.0
                self.sunset_hour = sunset_dt.hour + sunset_dt.minute / 60.0 + sunset_dt.second / 3600.0
                print(f"[ClockWidget] Sun times computed: {self.sunrise_hour:.2f} to {self.sunset_hour:.2f}")
            except Exception as e:
                print(f"[ClockWidget] Could not compute sunrise/sunset: {e}")
                self.sunrise_hour = None
                self.sunset_hour = None
            finally:
                if getattr(self, '_initial_shown', False):
                    self.root.after(0, self.draw_clock)
                else:
                    self._initial_shown = True
                    self.root.after(0, self.show_initial)

        threading.Thread(target=_fetch, daemon=True).start()

    def update_fitbit_data(self, force: bool = False):
        """
        Background task to fetch latest Fitbit sleep & HR.
        Calls FitbitClient and updates self.fitbit_data.
        """
        # Safely extract Tkinter variables on the main thread before starting the background task
        bedtime_goal = self.bedtime_goal.get()
        include_naps = self.include_naps.get()
        excluded_dates = list(self.excluded_dates) # Thread-safe copy

        def _task():
            try:
                inputs = self.fitbit.get_all_energy_inputs(
                    bedtime_goal_hours=bedtime_goal,
                    include_naps=include_naps,
                    force=force,
                    excluded_dates=excluded_dates
                )
                
                # If we got here, reauth is NOT needed
                self.root.after(0, lambda: self._set_reauth_status('fitbit', False))

                # ... (rest of the logic)
                
                # Print the dynamic calculation for user visibility
                eff = inputs.get('empirical_efficiency', 1.0)
                need = inputs.get('sleep_need_hours', bedtime_goal)
                print(f"[Fitbit] Dynamic Efficiency: {eff:.1%}")
                print(f"[Fitbit] Dynamic Sleep Need: {need:.2f}h")

                # Collect results for main-thread application (no shared state mutation here)
                raw_logs = inputs.get('raw_sleep_logs', [])
                today_str = datetime.datetime.now().strftime("%Y-%m-%d")
                has_today_log = any(log.get('dateOfSleep') == today_str for log in raw_logs)

                # Check if we have an explicit saved setting for today yet (file I/O is safe)
                has_saved_setting = False
                if os.path.exists(self.sleep_settings_file):
                    try:
                        with open(self.sleep_settings_file, 'r') as f:
                            saved = json.load(f).get('explicit_dates', [])
                            if today_str in saved:
                                has_saved_setting = True
                    except Exception:
                        pass

                should_save_image = (
                    (not inputs.get('from_cache', False) and inputs.get('is_real_today', False))
                    or not self._has_today_image()
                )

                # Schedule ALL state mutations on the main thread to avoid data races
                self.root.after(0, lambda: self._apply_fitbit_results(
                    inputs, today_str, has_today_log, has_saved_setting, should_save_image
                ))

            except ReauthRequiredError:
                print("[update_fitbit_data] Fitbit re-authentication required.")
                self.root.after(0, lambda: self._set_reauth_status('fitbit', True))
            except Exception as e:
                print(f"[update_fitbit_data] Fitbit background update failed: {e}")
       
        threading.Thread(target=_task, daemon=True).start()

    def _apply_fitbit_results(self, inputs, today_str, has_today_log, has_saved_setting, should_save_image):
        """Apply Fitbit fetch results on the main thread (thread-safe)."""
        eff   = inputs.get('empirical_efficiency', 1.0)
        need  = inputs.get('sleep_need_hours', self.bedtime_goal.get())
        wake  = inputs.get('wake_hour')
        start = inputs.get('sleep_hour')
        dur   = inputs.get('sleep_duration')
        debt  = inputs.get('sleep_debt_hours', 0.0)
        bathy = inputs.get('bathyphase_hour')

        self.raw_sleep_logs = inputs.get('raw_sleep_logs', [])
        self._sleep_table_needs_refresh = True

        # Handle auto-inclusion logic for Today (T-0)
        if not has_saved_setting:
            if has_today_log and today_str in self.excluded_dates:
                self.excluded_dates.remove(today_str)
                self._save_sleep_settings()
            elif not has_today_log and today_str not in self.excluded_dates:
                self.excluded_dates.append(today_str)
                self._save_sleep_settings()

        if wake is not None:
            self.wake_hour          = wake
            self.sleep_hour         = start
            self.sleep_duration     = dur
            self.sleep_debt_hours   = debt if debt is not None else 0.0
            self.bathyphase_hour    = bathy
            self.sleep_efficiency   = eff
            self.sleep_need_hours   = need
            self.raw_sleep_logs     = inputs.get('raw_sleep_logs', [])
            self.active_sleep_date  = inputs.get('active_sleep_date')

            self.last_fitbit_update = datetime.datetime.now()

            # Push all values to the EnergyCurve instance so draw() uses them
            self.energy_curve.sleep_debt_hours = self.sleep_debt_hours
            self.energy_curve.sleep_duration   = self.sleep_duration
            self.energy_curve.bathyphase_hour  = self.bathyphase_hour

            self.update_metric_labels()
            self.populate_sleep_table()
            self.draw_clock()

            if should_save_image:
                self.root.after(1000, self.save_clock_image)
        else:
            print("[update_fitbit_data] wake_hour still None after fetch.")

    def update_calendar_data(self):
        """Fetch today's events from Google Calendar in the background."""
        if getattr(self, '_calendar_refreshing', False):
            print("[ClockWidget] Calendar refresh already in progress, skipping.")
            return
        
        self._calendar_refreshing = True

        def _task():
            try:
                if not getattr(self, 'calendar_svc', None):
                    try:
                        print("[ClockWidget] Initializing Google Calendar service...")
                        self.calendar_svc = get_calendar_service()
                    except Exception as e:
                        print(f"[Calendar] Failed to initialize service: {e}")
                        return

                if not self.calendar_svc:
                    print("[ClockWidget] Calendar service is None, aborting refresh.")
                    return
                
                print("[ClockWidget] Fetching calendar events...")
                events = get_calendar_events(self.calendar_svc, 'primary')
                self.calendar_events = events
                self._last_calendar_refresh_time = datetime.datetime.now()
                print(f"[Calendar] Successfully fetched {len(events)} events.")
                self.root.after(0, lambda: (self._set_reauth_status('calendar', False), self.draw_clock()))
            except CalReauthRequiredError:
                print("[Calendar] Re-authentication required.")
                self.root.after(0, lambda: self._set_reauth_status('calendar', True))
            except Exception as e:
                print(f"[Calendar] Update failed: {e}")
            finally:
                self._calendar_refreshing = False
        
        threading.Thread(target=_task, daemon=True).start()

    def _build_sleep_table(self):
        """Build the styled sleep log table inside sleep_table_window."""
        ACCENT  = "#00ffcc"
        HDR_BG  = "#1e1e1e"
        BG      = self.solid_bg

        outer = tk.Frame(self.sleep_table_window, bg=HDR_BG, bd=0)
        outer.pack(fill=tk.BOTH, expand=True)

        # Draggable title bar
        title_bar = tk.Frame(outer, bg=HDR_BG)
        title_bar.pack(fill=tk.X)
        title_lbl = tk.Label(
            title_bar, text="  Sleep Log", bg=HDR_BG, fg=ACCENT,
            font=("Segoe UI", 10, "bold"), anchor="w", padx=4, pady=6
        )
        title_lbl.pack(side=tk.LEFT, fill=tk.X, expand=True)
        # Bind drag to title bar only
        for w in (title_bar, title_lbl):
            w.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.sleep_table_window))
            w.bind("<B1-Motion>", self.on_drag_motion)

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
        if parent.winfo_children():
            sep = tk.Frame(parent, bg="#444444", height=1)
            sep.pack(fill=tk.X, pady=(5, 5))
            self._control_widgets.append(sep)
        
        lbl = tk.Label(parent, text=text, bg=self.solid_bg, fg="white", font=("Arial", 8, "bold underline"))
        lbl.pack(side=tk.TOP, anchor=tk.W, pady=(0, 2))
        self._control_widgets.append(lbl)

    def _add_toggle(self, text, var, cmd, parent, tooltip=None):
        cb = tk.Checkbutton(
            parent,
            text=text,
            variable=var,
            command=cmd,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        cb.pack(side=tk.TOP, anchor=tk.W)
        if tooltip:
            Tooltip(cb, tooltip)
        self._control_widgets.append(cb)
        return cb

    def _add_slider(self, text, var, from_, to, parent, resolution=0.1, command=None, tooltip=None):
        frame = tk.Frame(parent, bg=self.solid_bg)
        frame.pack(side=tk.TOP, fill=tk.X)
        lbl = tk.Label(frame, text=text, bg=self.solid_bg, fg="white", font=("Arial", 7))
        lbl.pack(side=tk.TOP, anchor=tk.W)
        
        s = tk.Scale(
            frame, variable=var, from_=from_, to=to, resolution=resolution,
            orient=tk.HORIZONTAL, bg=self.solid_bg, fg="white",
            highlightthickness=0, troughcolor="#404040", activebackground="#00ffcc",
            showvalue=True, font=("Arial", 7)
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
        """Build the sleep model settings window."""
        ACCENT  = "#00ffcc"
        HDR_BG  = "#1e1e1e"
        BG      = self.solid_bg

        outer = tk.Frame(self.sleep_model_window, bg=HDR_BG, bd=0)
        outer.pack(fill=tk.BOTH, expand=True)

        # Draggable title bar
        title_bar = tk.Frame(outer, bg=HDR_BG)
        title_bar.pack(fill=tk.X)
        title_lbl = tk.Label(
            title_bar, text="  Sleep Model", bg=HDR_BG, fg=ACCENT,
            font=("Segoe UI", 10, "bold"), anchor="w", padx=4, pady=6
        )
        title_lbl.pack(side=tk.LEFT, fill=tk.X, expand=True)
        # Bind drag to title bar only
        for w in (title_bar, title_lbl):
            w.bind("<ButtonPress-1>", lambda e: self.on_drag_start(e, self.sleep_model_window))
            w.bind("<B1-Motion>", self.on_drag_motion)

        content = tk.Frame(outer, bg=BG)
        content.pack(fill=tk.BOTH, expand=True, padx=10, pady=(0, 10))

        def on_model_change():
            self.draw_clock()
            self._save_sleep_settings()
            # If bedtime goal changed, we might need a debt recalculation
            self._recalculate_debt()
            
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
        reset_btn = tk.Button(
            content, text="Reset", command=reset_model_defaults,
            bg="#404040", fg="#ff6b6b", activebackground="#555555",
            activeforeground="white", relief=tk.FLAT, padx=10, pady=2,
            font=("Arial", 8, "bold")
        )
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
            self.draw_clock()
        except Exception as e:
            print(f"[_recalculate_debt] Error: {e}")


    def populate_sleep_table(self):
        """Populate the sleep table from self.raw_sleep_logs."""
        if not hasattr(self, 'table_frame') or not hasattr(self, '_table_cols'):
            return

        # Remove previous data rows (keep row 0=headers, row 1=separator)
        for widget in self.table_frame.grid_slaves():
            if int(widget.grid_info().get('row', 0)) >= 2:
                widget.destroy()

        TODAY_STR = datetime.datetime.now().strftime("%Y-%m-%d")
        ACCENT    = "#00ffcc"
        ROW_ODD   = "#2f2f2f"
        ROW_EVN   = "#272727"
        TODAY_BG  = "#2a2a4a"
        TODAY_FG  = "#aaddff"
        FG        = "#e0e0e0"

        # Group by date
        daily_logs = {}
        for rec in self.raw_sleep_logs:
            d = rec.get('dateOfSleep', '?')
            if d not in daily_logs: daily_logs[d] = []
            daily_logs[d].append(rec)
        
        today_dt = datetime.datetime.now()
        
        # We'll display T-0 to T-14 (15 rows total)
        display_dates = []
        for i in range(15):
            display_dates.append((today_dt - datetime.timedelta(days=i)).strftime("%Y-%m-%d"))

        grid_row        = 2
        total_raw       = 0.0
        total_wtd       = 0.0
        
        # Lists for calculating averages
        start_hours     = []
        end_hours       = []
        durations       = []
        efficiencies    = []

        def toggle_date(date_str, var):
            # Mark this date as having a manual/explicit setting
            if not hasattr(self, 'explicit_dates'): self.explicit_dates = []
            if date_str not in self.explicit_dates:
                self.explicit_dates.append(date_str)

            if var.get():
                if date_str in self.excluded_dates:
                    self.excluded_dates.remove(date_str)
            else:
                if date_str not in self.excluded_dates:
                    self.excluded_dates.append(date_str)
            self._save_sleep_settings()
            
            # --- Instant Local Recalculation ---
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
                
                # Refresh UI immediately
                self.update_metric_labels()
                self.populate_sleep_table()
                self.draw_clock()
            except Exception as e:
                print(f"[toggle_date] Instant refresh failed: {e}")

            # Still trigger the background task
            self.update_fitbit_data()

        for i, date_str in enumerate(display_dates):
            is_excluded = date_str in self.excluded_dates
            
            # 1. Inclusion Checkbox
            cb_var = tk.BooleanVar(value=not is_excluded)
            cb = tk.Checkbutton(
                self.table_frame,
                variable=cb_var,
                command=lambda d=date_str, v=cb_var: toggle_date(d, v),
                bg=TODAY_BG if date_str == TODAY_STR else (ROW_ODD if i % 2 == 0 else ROW_EVN),
                selectcolor="#3c3c3c",
                activebackground=TODAY_BG if date_str == TODAY_STR else (ROW_ODD if i % 2 == 0 else ROW_EVN),
                bd=0,
                padx=0,
                pady=0
            )
            cb.grid(row=grid_row, column=0, sticky="nsew")

            logs = daily_logs.get(date_str, [])
            
            if logs:
                main_sleep = next((s for s in logs if s.get('isMainSleep')), logs[0])
                # ... rest of data extraction ...
                start_raw   = main_sleep.get('startTime', '')
                end_raw     = main_sleep.get('endTime', '')
                mins_asleep = sum(s.get('minutesAsleep', 0) for s in logs)
                mins_in_bed = sum(s.get('timeIn_bed', s.get('timeInBed', 0)) for s in logs)
                asleep_h    = mins_asleep / 60.0
                eff_val     = (mins_asleep / mins_in_bed * 100) if mins_in_bed > 0 else None
                
                try:
                    st        = datetime.datetime.fromisoformat(start_raw.replace('Z', ''))
                    start_fmt = st.strftime("%I:%M %p").lstrip('0')
                    if not is_excluded: start_hours.append(st.hour + st.minute / 60.0)
                except: start_fmt = start_raw[:5] if start_raw else '?'

                try:
                    en        = datetime.datetime.fromisoformat(end_raw.replace('Z', ''))
                    end_fmt   = en.strftime("%I:%M %p").lstrip('0')
                    if not is_excluded: end_hours.append(en.hour + en.minute / 60.0)
                except: end_fmt = end_raw[:5] if end_raw else '?'
            else:
                start_fmt   = "—"
                end_fmt     = "—"
                asleep_h    = 0.0
                eff_val     = None
                
            bg_color = TODAY_BG if date_str == TODAY_STR else (ROW_ODD if i % 2 == 0 else ROW_EVN)
            row_fg   = TODAY_FG if date_str == TODAY_STR else FG
            if is_excluded:
                row_fg = "#666666"

            # Format values
            try:
                dt_obj   = datetime.datetime.strptime(date_str, "%Y-%m-%d")
                day_fmt  = dt_obj.strftime("%a")
                date_fmt = dt_obj.strftime("%m/%d")
            except Exception:
                day_fmt  = ""
                date_fmt = date_str

            dur_fmt   = f"{asleep_h:.1f}h"
            if not is_excluded: durations.append(asleep_h)
            
            eff_fmt   = f"{eff_val:.0f}%" if eff_val is not None else "—"
            if eff_val is not None and not is_excluded:
                efficiencies.append(eff_val)
            
            debt_val  = (self.sleep_need_hours - asleep_h)
            wtd_val   = debt_val * (0.9 ** i)
            
            if not is_excluded:
                total_raw += debt_val
                total_wtd += wtd_val
            
            debt_fg   = "#ff6b6b" if debt_val > 0.1 else "#6bff6b" if debt_val < -0.1 else row_fg
            if is_excluded: debt_fg = "#442222" if debt_val > 0.1 else "#224422"

            cell_data = [
                (day_fmt,   "center",  row_fg),
                (date_fmt,  "e",  row_fg),
                (start_fmt, "e",  row_fg),
                (end_fmt,   "e",  row_fg),
                (dur_fmt,   "e",  row_fg),
                (eff_fmt,   "e",  row_fg),
                (f"{debt_val:+.1f}h",  "e",  debt_fg),
                (f"{wtd_val:+.1f}h",   "e",  debt_fg),
            ]

            for col_i, (val, anc, fg_color) in enumerate(cell_data):
                lbl = tk.Label(
                    self.table_frame, text=val,
                    bg=bg_color, fg=fg_color,
                    font=("Segoe UI", 9),
                    anchor=anc,
                    padx=6
                )
                lbl.grid(row=grid_row, column=col_i + 1, sticky="ew", pady=1)
            grid_row += 1

        # Summary Row (Averages / Totals)
        sep = tk.Frame(self.table_frame, bg="#444444", height=1)
        sep.grid(row=grid_row, column=0, columnspan=len(self._table_cols), sticky="ew", pady=2)
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
        
        for col_i, (val, anc, fg_color) in enumerate(summary_data):
            lbl = tk.Label(
                self.table_frame, text=val,
                bg="#1a1a1a", fg=fg_color,
                font=("Segoe UI", 9, "bold"),
                anchor=anc,
                padx=6
            )
            lbl.grid(row=grid_row, column=col_i + 1, sticky="ew", pady=2)

        # Shrink-wrap window to exact content size
        self.sleep_table_window.update_idletasks()
        self.sleep_table_window.geometry("")
        self._sleep_table_needs_refresh = False

    def update_metric_labels(self):
        """Update the Bathyphase and Efficiency labels in the controls window."""
        if self.bathyphase_hour is not None:
            h = int(self.bathyphase_hour)
            m = int((self.bathyphase_hour - h) * 60)
            am_pm = "AM" if h < 12 else "PM"
            h_display = h % 12
            if h_display == 0: h_display = 12
            self.bathyphase_label.config(text=f"Bathyphase: {h_display}:{m:02d} {am_pm}")
        else:
            self.bathyphase_label.config(text="Bathyphase: --:-- --")
            
        if self.sleep_efficiency is not None:
            self.efficiency_label.config(text=f"Sleep Efficiency: {self.sleep_efficiency*100:.1f}%")
        else:
            self.efficiency_label.config(text="Sleep Efficiency: --.-%")

    def show_initial(self):
        self.make_clock_transparent()
        self.make_controls_hidden()
        self.update_fitbit_data()
        self.update_calendar_data()
        self.draw_clock()
        self.root.deiconify()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.setup_tray_icon()

    def on_close(self):
        if hasattr(self, 'tray_icon'):
            self.tray_icon.stop()
        self.root.destroy()

    def create_tray_image(self, now):
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
        self.make_solid()

    def toggle_widget(self):
        if self.root.winfo_viewable():
            self.root.withdraw()
        else:
            self.show_widget()

    def setup_tray_icon(self):
        if getattr(self, '_tray_initialized', False):
            return
        self._tray_initialized = True

        def on_quit(icon, item):
            icon.stop()
            self.root.after(0, self.root.destroy)

        def on_toggle(icon, item=None):
            self.root.after(0, self.toggle_widget)
           
        def on_reauth(icon, item=None):
            self.root.after(0, self.fitbit.authorize)

        self.tray_menu = pystray.Menu(
            pystray.MenuItem('Show/Hide Clock', on_toggle, default=True),
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
                self.draw_clock()

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
                self.update_fitbit_data(force=True)
                self.update_calendar_data()
            # 2. 10-minute polling (Fitbit + Calendar + Celestial)
            elif now.minute % 10 == 0:
                print(f"[ClockWidget] 10-minute polling refresh at {now}")
                self.update_fitbit_data()
                self.update_calendar_data()

            self._last_drawn_minute = current_minute
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
            try:
                self.tk_icon = ImageTk.PhotoImage(img)
                self.root.iconphoto(True, self.tk_icon)
            except Exception:
                pass
               
        seconds_to_next_mark = 10 - (now.second % 10)
        ms_to_next_mark = seconds_to_next_mark * 1000 - int(now.microsecond / 1000)
        self.root.after(max(100, ms_to_next_mark + 50), self.update_clock)

    def _update_hand_only(self, now):
        """Lightweight redraw: only move the clock hand between full redraws."""
        self.canvas.delete("clock_hand")
        self.canvas.delete("solar_circle")
        w = self.canvas.winfo_width()
        h = self.canvas.winfo_height()
        center_x, center_y = w / 2, h / 2
        radius = min(w, h) / 2 - self.FACE_PADDING
        if radius < 20:
            return
        
        self._draw_solar_circle(center_x, center_y, radius)
        self._draw_clock_hand(now, center_x, center_y, radius)

    def _apply_energy_curve_params(self):
        self.energy_curve.wake_hour = self.wake_hour
        self.energy_curve.sleep_debt_hours = self.sleep_debt_hours if self.show_sleep_debt.get() else 0.0
        self.energy_curve.sleep_duration = self.sleep_duration
        self.energy_curve.bathyphase_hour = self.bathyphase_hour if self.use_bathyphase.get() else None
        
        self.energy_curve.tau_wake = self.tau_wake.get()
        self.energy_curve.tau_sleep = self.tau_sleep.get()
        self.energy_curve.tau_inertia = self.tau_inertia.get()
        self.energy_curve.debt_factor = self.debt_factor.get()
        self.energy_curve.circadian_peak_offset = None if self.use_bathyphase.get() else self.circadian_offset.get()
        
        self.energy_curve.max_rested_energy = self._get_max_energy()

    def _draw_clock_hand(self, now, center_x, center_y, radius):
        current_hour = now.hour + now.minute / 60.0 + now.second / 3600.0
        hand_angle = (18 - current_hour) * 15
        hand_rad = math.radians(hand_angle)
       
        hx = center_x + radius * math.cos(hand_rad)
        hy = center_y - radius * math.sin(hand_rad)
       
        arrow_len = radius * 0.12
        hand_width = max(2, int(radius/25))
        self.canvas.create_line(
            center_x, center_y, hx, hy,
            fill="#FF9F1C", width=hand_width,
            arrow=tk.LAST, arrowshape=(arrow_len, arrow_len, arrow_len/3),
            capstyle=tk.ROUND,
            tags="clock_hand"
        )
        
        # Add a circular cap at the center for a perfectly rounded terminus
        dot_r = hand_width / 2
        self.canvas.create_oval(
            center_x - dot_r, center_y - dot_r,
            center_x + dot_r, center_y + dot_r,
            fill="#FF9F1C", outline="",
            tags="clock_hand"
        )
        
        if self.wake_hour is not None:
            self._apply_energy_curve_params()
            current_e = self.energy_curve.get_cached_energy(current_hour)
            
            # max energy today if fully rested
            max_e = self._get_max_energy()
                
            if max_e > 0 and self.show_energy_pct.get():
                pct = max(0, int(round((current_e / max_e) * 100)))
                # Text at the tip of the hand
                icon_size = max(12, int(radius / 7))
                tip_offset = icon_size + 10
                tx = center_x + (radius + tip_offset) * math.cos(hand_rad)
                ty = center_y - (radius + tip_offset) * math.sin(hand_rad)
                
                font_size = max(8, int(radius / 11))
                text_color = self.energy_curve.interpolate_color(current_e)
                
                # Draw black outline (4 directions)
                for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    self.canvas.create_text(
                        tx + dx, ty + dy,
                        text=f"{pct}%",
                        fill="black",
                        font=("Segoe UI", font_size, "bold"),
                        tags="clock_hand"
                    )

                self.canvas.create_text(
                    tx, ty,
                    text=f"{pct}%",
                    fill=text_color,
                    font=("Segoe UI", font_size, "bold"),
                    tags="clock_hand"
                )

    def _get_celestial_positions(self, current_hour):
        """Return (sun_rad, moon_rad, m_phase_val), calculating current elevation every minute but caching extremes for 5 minutes."""
        now = datetime.datetime.now()
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        
        # 1. Update extremes cache if needed (every 5 minutes)
        if not hasattr(self, '_extremes_cache') or (now - self._extremes_cache['ts']).total_seconds() >= 300:
            try:
                user_lat = getattr(self, 'lat', None)
                user_lon = getattr(self, 'lon', None)
                if user_lat is None or user_lon is None:
                    raise ValueError("Lat/Lon not yet available")
                
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
                # Fallback to simple time-based approximation
                s_rad = math.radians((18 - current_hour) * 15)
                m_phase_val = 0
                moon_hour = (current_hour - (m_phase_val / 29.53) * 24) % 24
                m_rad = math.radians((18 - moon_hour) * 15)
                return s_rad, m_rad, m_phase_val

        # 2. Calculate current positions every minute using cached extremes
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
        
        return s_rad, m_rad, m_phase_val

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


        self.canvas.create_oval(
            center_x - circle_r, center_y - circle_r,
            center_x + circle_r, center_y + circle_r,
            fill=fill_color,
            outline="",
            width=1,
            tags="solar_circle"
        )

    def on_canvas_motion(self, event):
        """Handle tooltip display for calendar events."""
        # Hide existing tooltip first
        if self._tooltip_id:
            self.canvas.delete(self._tooltip_id)
            self._tooltip_id = None
        if self._tooltip_bg_id:
            self.canvas.delete(self._tooltip_bg_id)
            self._tooltip_bg_id = None

        # Find item under cursor
        item = self.canvas.find_withtag("current")
        if not item:
            return
            
        item_id = item[0]
        if item_id in self._calendar_item_map:
            summary = self._calendar_item_map[item_id]
            
            # Create tooltip text
            self._tooltip_id = self.canvas.create_text(
                event.x, event.y - 20,
                text=summary,
                fill="white",
                font=("Segoe UI", 9, "bold"),
                anchor=tk.S,
                state=tk.DISABLED # Transparent to clicks
            )
            
            # Create tooltip background
            bbox = self.canvas.bbox(self._tooltip_id)
            if bbox:
                self._tooltip_bg_id = self.canvas.create_rectangle(
                    bbox[0]-4, bbox[1]-2, bbox[2]+4, bbox[3]+2,
                    fill="#1a1a1a", outline="#9370DB", width=1,
                    state=tk.DISABLED # Transparent to clicks
                )
                self.canvas.tag_lower(self._tooltip_bg_id, self._tooltip_id)

    def _set_reauth_status(self, service, needed):
        if service == 'fitbit':
            self.reauth_needed_fitbit = needed
        else:
            self.reauth_needed_calendar = needed
        
        # Update UI if needed
        self.draw_clock()

    def draw_clock(self):
        self.canvas.delete("all")
        # ... (rest of the function)
        self._calendar_item_map.clear() # Clear item map on redraw
        w = self.canvas.winfo_width()
        h = self.canvas.winfo_height()
       
        if w < 10 or h < 10:
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
           
        def draw_energy_curve():
            if self.show_energy.get() and self.wake_hour is not None:
                self._apply_energy_curve_params()
                self.energy_curve.normalize = self.normalize_energy.get()
                self.energy_curve.draw(center_x, center_y, radius, self.wake_hour)

        def draw_energy_indicators():
            if self.show_energy.get() and self.wake_hour is not None:
                # Draw Visual Indicators (Bathyphase and Acrophase)
                if self.show_bathyphase.get() and self.bathyphase_hour is not None:
                    bathy_e = get_energy_level(
                        self.bathyphase_hour, self.wake_hour, self.sleep_debt_hours, self.sleep_duration,
                        self.bathyphase_hour if self.use_bathyphase.get() else None,
                        clamp=False,
                        tau_wake=self.tau_wake.get(),
                        tau_sleep=self.tau_sleep.get(),
                        tau_inertia=self.tau_inertia.get(),
                        debt_factor=self.debt_factor.get(),
                        circadian_peak_offset=None if self.use_bathyphase.get() else self.circadian_offset.get()
                    )
                    self.energy_curve.draw_bathyphase_indicator(center_x, center_y, radius, self.bathyphase_hour, bathy_e)

                if self.show_acrophase.get():
                    max_e = getattr(self.energy_curve, '_cached_e_max', None)
                    if max_e is not None:
                        try:
                            idx = self.energy_curve._cached_levels.index(max_e)
                            acro_h = (idx / (len(self.energy_curve._cached_levels) - 1)) * 24.0
                            self.energy_curve.draw_acrophase_indicator(center_x, center_y, radius, acro_h, max_e)
                        except: pass

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
                sun_rad, moon_rad, m_phase_val = self._get_celestial_positions(current_hour)
                icon_size = max(12, int(radius / 7))
                orbit_radius = radius + icon_size + 2

                sx = center_x + orbit_radius * math.cos(sun_rad)
                sy = center_y - orbit_radius * math.sin(sun_rad)
                sun_r = icon_size / 1.6
                self.canvas.create_oval(
                    sx - sun_r, sy - sun_r, sx + sun_r, sy + sun_r,
                    fill="#FFD700", outline="#FFA500", width=2
                )

                mx = center_x + orbit_radius * math.cos(moon_rad)
                my = center_y - orbit_radius * math.sin(moon_rad)
                
                # Manual drawing of the moon phase to fix rendering/coloring issues.
                # This ensures the lit part is always light and the unlit part is dark,
                # regardless of how the OS/font renders moon emojis.
                p = (m_phase_val / 29.530588) % 1.0
                m_r = icon_size / 1.6
                
                # Base unlit moon (shadow)
                self.canvas.create_oval(
                    mx - m_r, my - m_r, mx + m_r, my + m_r,
                    fill="#444444", outline="", tags="sun_and_moon"
                )
                
                # Lit part calculation
                if 0.01 < p < 0.99: # Not a New Moon
                    if p <= 0.5: # Waxing (lit on the right)
                        self.canvas.create_arc(
                            mx - m_r, my - m_r, mx + m_r, my + m_r,
                            start=-90, extent=180,
                            fill="#E0E0E0", outline="", style=tk.PIESLICE, tags="sun_and_moon"
                        )
                        mid_p = (p * 4) - 1
                        e_width = abs(mid_p) * m_r
                        e_color = "#444444" if mid_p < 0 else "#E0E0E0"
                        self.canvas.create_oval(
                            mx - e_width, my - m_r, mx + e_width, my + m_r,
                            fill=e_color, outline="", tags="sun_and_moon"
                        )
                    else: # Waning (lit on the left)
                        self.canvas.create_arc(
                            mx - m_r, my - m_r, mx + m_r, my + m_r,
                            start=90, extent=180,
                            fill="#E0E0E0", outline="", style=tk.PIESLICE, tags="sun_and_moon"
                        )
                        mid_p = ((p - 0.5) * 4) - 1
                        e_width = abs(mid_p) * m_r
                        e_color = "#E0E0E0" if mid_p < 0 else "#444444"
                        self.canvas.create_oval(
                            mx - e_width, my - m_r, mx + e_width, my + m_r,
                            fill=e_color, outline="", tags="sun_and_moon"
                        )
                elif 0.49 <= p <= 0.51: # Full Moon fallback
                    self.canvas.create_oval(
                        mx - m_r, my - m_r, mx + m_r, my + m_r,
                        fill="#E0E0E0", outline="", tags="sun_and_moon"
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
                self.canvas.tag_bind("reauth_icon_fitbit", "<Button-1>", lambda e: self.fitbit.authorize())
                Tooltip(self.canvas, "Fitbit Auth Expired - Click to Re-link")
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
                Tooltip(self.canvas, "Calendar Auth Expired - Click to Re-link")

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

        # Draw in the configured order
        for layer_name in self.DRAW_ORDER:
            if layer_name in layers:
                layers[layer_name]()

    def save_clock_image(self):
        """Saves a high-resolution (1200x1200px) image of the clock face to 'saved_curves/'."""
        if self.wake_hour is None:
            return

        # Ensure directory exists
        folder = os.path.join(os.path.dirname(__file__), "saved_curves")
        if not os.path.exists(folder):
            os.makedirs(folder)
            
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        filepath = os.path.join(folder, f"{timestamp}_energy_curve.png")
        
        # High-res rendering (1200x1200px)
        size = 1200
        img = Image.new('RGB', (size, size), "#2b2b2b") # Use solid_bg value
        draw = ImageDraw.Draw(img)
        
        center_x = size / 2
        center_y = size / 2
        
        # Proportional radius (matching the widget's aspect ratio)
        scale = size / 230.0
        radius = 77 * scale
        
        # 1. Background Face
        draw.ellipse([center_x - radius, center_y - radius, center_x + radius, center_y + radius], fill="#ffffff", outline="black", width=int(3 * scale))
        
        # 2. Night Shading
        if self.sunrise_hour is not None and self.sunset_hour is not None:
            night_hours = self.sunrise_hour - self.sunset_hour
            if night_hours < 0: night_hours += 24
            sunset_angle_tk = (18 - self.sunset_hour) * 15
            extent_tk = -(night_hours * 15)
            
            pil_start = -sunset_angle_tk
            pil_end = pil_start - extent_tk
            draw.pieslice([center_x - radius, center_y - radius, center_x + radius, center_y + radius], start=pil_start, end=pil_end, fill="#2C3E50")

        # 3. Sleep Arc
        if self.wake_hour is not None:
            if self.show_total_bedtime.get():
                start_h = self.sleep_hour
            else:
                start_h = (self.wake_hour - self.sleep_duration) % 24 if self.sleep_duration else self.sleep_hour
            
            if start_h is not None:
                display_dur = self.wake_hour - start_h
                if display_dur < 0: display_dur += 24
                sleep_start_angle = (18 - start_h) * 15
                sleep_extent = -(display_dur * 15)
                
                margin = radius * 0.15
                bbox_sleep = [center_x - (radius - margin), center_y - (radius - margin), 
                              center_x + (radius - margin), center_y + (radius - margin)]
                draw.pieslice(bbox_sleep, start=-sleep_start_angle, end=-sleep_start_angle - sleep_extent, fill="#6A5ACD")

        # 4. Energy Curve (Always Normalized for the archive)
        old_norm = self.energy_curve.normalize
        self.energy_curve.normalize = True
        self.energy_curve.draw(center_x, center_y, radius, self.wake_hour, draw_obj=draw, width_scale=scale)
        self.energy_curve.normalize = old_norm
        
        # 5. Perimeter Line
        draw.ellipse([center_x - radius, center_y - radius, center_x + radius, center_y + radius], outline="black", width=int(2 * scale))
        
        # 6. Ticks (No Numbers)
        for h_tick in range(0, 24, 2):
            angle = (18 - h_tick) * 15
            rad = math.radians(angle)
            tick_len = radius * 0.12 if h_tick % 6 == 0 else radius * 0.08
            x1 = center_x + (radius - tick_len) * math.cos(rad)
            y1 = center_y - (radius - tick_len) * math.sin(rad)
            x2 = center_x + radius * math.cos(rad)
            y2 = center_y - radius * math.sin(rad)
            
            if self.sunrise_hour is not None and self.sunset_hour is not None:
                is_night = (h_tick >= self.sunset_hour or h_tick < self.sunrise_hour) if self.sunset_hour > self.sunrise_hour else (h_tick >= self.sunset_hour and h_tick < self.sunrise_hour)
            else:
                is_night = False
            tick_color = "white" if is_night else "black"
            draw.line([(x1, y1), (x2, y2)], fill=tick_color, width=int(3 * scale if h_tick % 6 == 0 else 2 * scale))

        img.save(filepath)
        print(f"[save_clock_image] Saved energy snapshot to: {filepath}")

    def _has_today_image(self):
        """Returns True if at least one energy curve image exists for today."""
        folder = os.path.join(os.path.dirname(__file__), "saved_curves")
        if not os.path.exists(folder):
            return False
        date_str = datetime.datetime.now().strftime("%Y-%m-%d")
        try:
            for f in os.listdir(folder):
                if f.startswith(date_str):
                    return True
        except:
            pass
        return False



if __name__ == "__main__":
    root = tk.Tk()
    app = ClockWidget(root)
    root.mainloop()
