import tkinter as tk
from tkinter import ttk
import math
import datetime
import threading
import urllib.request
import json
from astral import Observer
from astral.sun import sun
from astral.moon import phase  
from PIL import Image, ImageDraw, ImageTk
import pystray
from fitbit_client import FitbitClient
from energy_logic import EnergyCurve

class ClockWidget:
    # --- CONFIGURE YOUR FITBIT CREDENTIALS HERE ---
    FITBIT_CLIENT_ID = 'YOUR_FITBIT_CLIENT_ID'
    FITBIT_CLIENT_SECRET = 'YOUR_FITBIT_CLIENT_SECRET'
    # -- PERSONAL GOALS ----------------------------
    # Your target time spent in bed (9h 45m = 9.75)
    # The app will automatically calculate your 'Sleep Need'
    # by multiplying this by your actual efficiency.
    BEDTIME_GOAL_HOURS = 9.75
    # ----------------------------------------------

    def __init__(self, root):
        self.root = root
        self.root.title("24h Clock")
       
        window_width = 200
        window_height = 550
        screen_width = self.root.winfo_screenwidth()
        screen_height = self.root.winfo_screenheight()
        center_x = int(screen_width / 2 - window_width / 2)
        center_y = int(screen_height / 2 - window_height / 2)
        self.root.geometry(f"{window_width}x{window_height}+{center_x}+{center_y}")

        self.root.minsize(120, 150)
        self.root.configure(bg="#2b2b2b")
       
        self.always_on_top = tk.BooleanVar(value=True)
        self.show_numbers = tk.BooleanVar(value=False)
        self.show_sleep = tk.BooleanVar(value=True)
        self.show_total_bedtime = tk.BooleanVar(value=True)
        self.show_energy = tk.BooleanVar(value=True)
        self.show_sleep_debt = tk.BooleanVar(value=True)
        self.normalize_energy = tk.BooleanVar(value=True)
        self.include_naps = tk.BooleanVar(value=True)
        self.show_sun_moon = tk.BooleanVar(value=True)
       
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
        self.last_fitbit_update = None
        # ─────────────────────────────────────────────────────────────────────
       
        # Transparency State
        self.transparent_key = "#010203"
        self.solid_bg = "#2b2b2b"
        self.is_transparent = False
        self.hover_timer = None
       
        # Make the key color fully transparent on Windows
        self.root.attributes("-transparentcolor", self.transparent_key)
       
        # Main Frame
        self.main_frame = tk.Frame(self.root, bg=self.solid_bg)
        self.main_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=0)
       
        # UI Elements
        self.canvas = tk.Canvas(self.main_frame, bg=self.solid_bg, highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)
       
        self.controls_frame = tk.Frame(self.main_frame, bg=self.solid_bg)
        self.controls_frame.pack(fill=tk.X, side=tk.BOTTOM, pady=0)
       
        self.top_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Always On Top",
            variable=self.always_on_top,
            command=self.toggle_topmost,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.top_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.numbers_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Numbers",
            variable=self.show_numbers,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.numbers_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.sleep_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Sleep",
            variable=self.show_sleep,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.sleep_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.bedtime_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Time in Bed vs. Asleep Hrs.",
            variable=self.show_total_bedtime,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.bedtime_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.energy_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Energy Curve",
            variable=self.show_energy,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.energy_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.debt_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Factor in Sleep Debt",
            variable=self.show_sleep_debt,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.debt_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.normalize_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Normalize Energy",
            variable=self.normalize_energy,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.normalize_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.naps_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Include Naps",
            variable=self.include_naps,
            command=self.update_fitbit_data,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.naps_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.sun_moon_toggle = tk.Checkbutton(
            self.controls_frame,
            text="Sun & Moon Icons",
            variable=self.show_sun_moon,
            command=self.draw_clock,
            bg=self.solid_bg,
            fg="white",
            selectcolor="#3c3c3c",
            activebackground=self.solid_bg,
            activeforeground="white"
        )
        self.sun_moon_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)

        self.refresh_btn = tk.Button(
            self.controls_frame,
            text="API Refresh",
            command=lambda: self.update_fitbit_data(force=True),
            bg="#404040",
            fg="#00ffcc",
            activebackground="#555555",
            activeforeground="white",
            relief=tk.FLAT,
            padx=10,
            pady=2,
            font=("Arial", 9, "bold")
        )
        self.refresh_btn.pack(side=tk.TOP, anchor=tk.E, padx=5, pady=10)
       
        self.sunrise_hour = 6.0
        self.sunset_hour = 18.0
       
        self.canvas.bind("<Configure>", self.on_resize)
       
        # Bind hover events
        self.root.bind("<Enter>", self.on_enter)
        self.root.bind("<Leave>", self.on_leave)
       
        # Bind drag events to main_frame and canvas
        for widget in (self.main_frame, self.canvas):
            widget.bind("<ButtonPress-1>", self.on_drag_start)
            widget.bind("<B1-Motion>", self.on_drag_motion)
       
        # Initialize Energy Curve logic
        self.energy_curve = EnergyCurve(self.canvas)

        # Start hidden and wait for sun times
        self.root.withdraw()
        self.fetch_sun_times()
        self.update_clock()

    def toggle_topmost(self):
        self.root.attributes('-topmost', self.always_on_top.get())

    def set_transparency(self, transparent):
        self.is_transparent = transparent
        bg_color = self.transparent_key if transparent else self.solid_bg
        self.root.configure(bg=bg_color)
        self.main_frame.configure(bg=bg_color)
        self.canvas.configure(bg=bg_color)
        self.controls_frame.configure(bg=bg_color)
        self.top_toggle.configure(bg=bg_color, activebackground=bg_color)
       
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

    def set_transparency(self, transparent):
        self.is_transparent = transparent
        bg_color = self.transparent_key if transparent else self.solid_bg
        self.root.configure(bg=bg_color)
        self.main_frame.configure(bg=bg_color)
        self.canvas.configure(bg=bg_color)
        self.controls_frame.configure(bg=bg_color)
        self.top_toggle.configure(bg=bg_color, activebackground=bg_color)
       
        rx = self.root.winfo_rootx()
        ry = self.root.winfo_rooty()
       
        bx, by = self.get_borders()
       
        if transparent:
            if self.root.winfo_viewable():
                self.root.geometry(f"+{rx}+{ry}")
            self.root.overrideredirect(True)
            self.top_toggle.pack_forget()
            self.numbers_toggle.pack_forget()
            self.sleep_toggle.pack_forget()
            self.bedtime_toggle.pack_forget()
            self.energy_toggle.pack_forget()
            self.debt_toggle.pack_forget()
            self.normalize_toggle.pack_forget()
            self.naps_toggle.pack_forget()
            self.sun_moon_toggle.pack_forget()
            self.refresh_btn.pack_forget()
        else:
            if self.root.winfo_viewable():
                self.root.geometry(f"+{rx - bx}+{ry - by}")
            self.root.overrideredirect(False)
            self.top_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.numbers_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.sleep_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.bedtime_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.energy_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.debt_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.normalize_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.naps_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.sun_moon_toggle.pack(side=tk.TOP, anchor=tk.W, padx=5)
            self.refresh_btn.pack(side=tk.TOP, anchor=tk.E, padx=5, pady=10)
           
        # Re-apply topmost which can sometimes be lost on Windows when toggling overrideredirect
        self.root.attributes('-topmost', self.always_on_top.get())

    def make_solid(self):
        self.set_transparency(False)
        self.check_nearby()

    def make_transparent(self):
        self.set_transparency(True)

    def check_nearby(self):
        if self.is_transparent:
            return
           
        mx = self.root.winfo_pointerx()
        my = self.root.winfo_pointery()
       
        rx = self.root.winfo_rootx()
        ry = self.root.winfo_rooty()
        rw = self.root.winfo_width()
        rh = self.root.winfo_height()
       
        margin = 60
        if mx < rx - margin or mx > rx + rw + margin or my < ry - margin or my > ry + rh + margin:
            self.make_transparent()
        else:
            self.root.after(100, self.check_nearby)

    def on_enter(self, event):
        if self.hover_timer is not None:
            self.root.after_cancel(self.hover_timer)
            self.hover_timer = None
        if self.is_transparent:
            self.hover_timer = self.root.after(750, self.make_solid)

    def on_drag_start(self, event):
        if self.is_transparent:
            self._drag_data = None
            return
           
        w = self.root.winfo_width()
        h = self.root.winfo_height()
        margin = 20
       
        rx = event.x_root - self.root.winfo_rootx()
        ry = event.y_root - self.root.winfo_rooty()
       
        if rx < margin or rx > w - margin or ry < margin or ry > h - margin:
            self._drag_data = None
            return
           
        self._drag_data = {'x': event.x_root, 'y': event.y_root,
                           'wx': self.root.winfo_x(), 'wy': self.root.winfo_y()}

    def on_drag_motion(self, event):
        if getattr(self, '_drag_data', None):
            dx = event.x_root - self._drag_data['x']
            dy = event.y_root - self._drag_data['y']
            self.root.geometry(f"+{self._drag_data['wx'] + dx}+{self._drag_data['wy'] + dy}")

    def on_leave(self, event):
        if self.hover_timer is not None:
            self.root.after_cancel(self.hover_timer)
            self.hover_timer = None

    def fetch_sun_times(self):
        def _fetch():
            lat, lon = None, None
            try:
                req = urllib.request.Request("http://ip-api.com/json/?fields=lat,lon", headers={'User-Agent': 'Mozilla/5.0'})
                resp = urllib.request.urlopen(req, timeout=3)
                data = json.loads(resp.read().decode())
                lat = data.get('lat')
                lon = data.get('lon')
            except Exception as e:
                print("IP location failed, using timezone fallback:", e)
               
            if lat is None or lon is None:
                offset_hours = datetime.datetime.now().astimezone().utcoffset().total_seconds() / 3600.0
                tz_map = {
                    -10: (21.3069, -157.8583), -9: (61.2181, -149.9003),
                    -8: (34.0522, -118.2437), -7: (39.7392, -104.9903),
                    -6: (41.8781, -87.6298), -5: (40.7128, -74.0060),
                    -4: (40.7128, -74.0060), 0: (51.5074, -0.1278),
                    1: (48.8566, 2.3522), 2: (30.0444, 31.2357),
                    3: (55.7558, 37.6173), 5.5: (28.6139, 77.2090),
                    8: (39.9042, 116.4074), 9: (35.6762, 139.6503),
                    10: (-33.8688, 151.2093)
                }
                closest = min(tz_map.keys(), key=lambda k: abs(k - offset_hours))
                lat, lon = tz_map[closest]

            try:
                self.lat = lat
                self.lon = lon
                obs = Observer(latitude=lat, longitude=lon)
                s = sun(obs, date=datetime.date.today())
               
                sunrise_dt = s['sunrise'].astimezone()
                sunset_dt = s['sunset'].astimezone()
               
                self.sunrise_hour = sunrise_dt.hour + sunrise_dt.minute / 60.0 + sunrise_dt.second / 3600.0
                self.sunset_hour = sunset_dt.hour + sunset_dt.minute / 60.0 + sunset_dt.second / 3600.0
            except Exception as e:
                print("Could not compute sunrise/sunset:", e)
            finally:
                self.root.after(0, self.show_initial)

        threading.Thread(target=_fetch, daemon=True).start()

    def update_fitbit_data(self, force: bool = False):
        """
        Background task to fetch latest Fitbit sleep & HR.
        Calls FitbitClient and updates self.fitbit_data.
        """
        def _task():
            try:
                inputs = self.fitbit.get_all_energy_inputs(
                    bedtime_goal_hours=self.BEDTIME_GOAL_HOURS,
                    include_naps=self.include_naps.get(),
                    force=force
                )
                
                # Print the dynamic calculation for user visibility
                eff = inputs.get('empirical_efficiency', 1.0)
                need = inputs.get('sleep_need_hours', self.BEDTIME_GOAL_HOURS)
                print(f"[Fitbit] Dynamic Efficiency: {eff:.1%}")
                print(f"[Fitbit] Dynamic Sleep Need: {need:.2f}h")

                wake    = inputs.get('wake_hour')
                start   = inputs.get('sleep_hour')   # not returned directly —
                                                      # derive from duration
                dur     = inputs.get('sleep_duration')
                debt    = inputs.get('sleep_debt_hours', 0.0)
                bathy   = inputs.get('bathyphase_hour')

                if wake is not None:
                    self.wake_hour          = wake
                    self.sleep_hour         = start # startTime from API
                    self.sleep_duration     = dur   # minutesAsleep / 60
                    self.sleep_debt_hours   = debt if debt is not None else 0.0
                    self.bathyphase_hour    = bathy

                    self.last_fitbit_update = datetime.datetime.now()

                    # Push all values to the EnergyCurve instance so draw() uses them
                    self.energy_curve.sleep_debt_hours = self.sleep_debt_hours
                    self.energy_curve.sleep_duration   = self.sleep_duration
                    self.energy_curve.bathyphase_hour  = self.bathyphase_hour

                    self.root.after(0, self.draw_clock)
                else:
                    print("[update_fitbit_data] wake_hour still None after fetch.")

            except Exception as e:
                print(f"[update_fitbit_data] Fitbit background update failed: {e}")
       
        threading.Thread(target=_task, daemon=True).start()

    def show_initial(self):
        self.set_transparency(True)
        self.update_fitbit_data()
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
        self.draw_clock()

    def update_clock(self):
        self.draw_clock()
        now = datetime.datetime.now()
       
        if not hasattr(self, 'last_tray_minute') or self.last_tray_minute != now.minute:
            self.last_tray_minute = now.minute
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

    def draw_clock(self):
        self.canvas.delete("all")
        w = self.canvas.winfo_width()
        h = self.canvas.winfo_height()
       
        if w < 10 or h < 10:
            return
           
        center_x = w / 2
        center_y = h / 2
        # --- UPDATE RADIUS PADDING ---
        # Increased the '- 5' to '- 25' to make room for the outer orbit
        radius = min(w, h) / 2 - 25
       
        if radius < 20:
            return

        self.canvas.create_oval(
            center_x - radius, center_y - radius,
            center_x + radius, center_y + radius,
            fill="#ffffff", outline="black", width=3
        )
       
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
       
        if self.show_sleep.get() and self.wake_hour is not None:
            # Decide which start point to use
            if self.show_total_bedtime.get():
                # Use startTime (Time in Bed)
                start_h = self.sleep_hour
            else:
                # Use wake minus duration (Actual Sleep)
                if self.sleep_duration is not None:
                    start_h = (self.wake_hour - self.sleep_duration) % 24
                else:
                    start_h = self.sleep_hour

            if start_h is not None:
                display_dur = self.wake_hour - start_h
                if display_dur < 0:
                    display_dur += 24
                    
                sleep_start_angle = (18 - start_h) * 15
                sleep_extent = - (display_dur * 15)
           
            sleep_margin = radius * 0.15
            self.canvas.create_arc(
                center_x - (radius - sleep_margin), center_y - (radius - sleep_margin),
                center_x + (radius - sleep_margin), center_y + (radius - sleep_margin),
                start=sleep_start_angle, extent=sleep_extent,
                fill="#6A5ACD", outline="", style=tk.PIESLICE
            )
       
        # Energy curve — EnergyCurve instance already has debt/duration/bathyphase
        # set on it by update_fitbit_data(); just pass wake_hour here.
        if self.show_energy.get() and self.wake_hour is not None:
            # Factor in debt only if toggle is on
            current_debt = self.sleep_debt_hours if self.show_sleep_debt.get() else 0.0
            self.energy_curve.sleep_debt_hours = current_debt
            self.energy_curve.normalize = self.normalize_energy.get()
            self.energy_curve.draw(center_x, center_y, radius, self.wake_hour)

        self.canvas.create_oval(
            center_x - radius, center_y - radius,
            center_x + radius, center_y + radius,
            outline="black", width=2
        )
       
        for h_tick in range(24):
            angle = (18 - h_tick) * 15
            rad = math.radians(angle)
           
            tick_len = radius * 0.12 if h_tick % 6 == 0 else (radius * 0.08 if h_tick % 2 == 0 else radius * 0.05)
           
            x1 = center_x + (radius - tick_len) * math.cos(rad)
            y1 = center_y - (radius - tick_len) * math.sin(rad)
            x2 = center_x + radius * math.cos(rad)
            y2 = center_y - radius * math.sin(rad)
           
            if self.sunset_hour > self.sunrise_hour:
                is_night = (h_tick >= self.sunset_hour) or (h_tick < self.sunrise_hour)
            else:
                is_night = (h_tick >= self.sunset_hour) and (h_tick < self.sunrise_hour)
               
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
               
        now = datetime.datetime.now()
        current_hour = now.hour + now.minute / 60.0 + now.second / 3600.0

        # --- SUN & MOON ICONS ---
        if self.show_sun_moon.get():
            # --- 1. COORDINATE SETUP ---
            # Icons sit just outside the circumference
            icon_size = max(12, int(radius / 7))
            orbit_radius = radius + (icon_size / 2) + 8 
            
            now_utc = datetime.datetime.now(datetime.timezone.utc)
            # Ensure lat/lon are available from the fetch_sun_times method
            user_lat = getattr(self, 'lat', 0)
            user_lon = getattr(self, 'lon', 0)

            try:
                from astral import Observer
                from astral.sun import azimuth as sun_azimuth
                from astral.moon import phase, azimuth as moon_azimuth
                
                user_lat = getattr(self, 'lat', None)
                user_lon = getattr(self, 'lon', None)
                
                if user_lat is None or user_lon is None:
                    raise ValueError("Lat/Lon not yet available")

                obs = Observer(latitude=user_lat, longitude=user_lon)
                
                # --- 2. ACCURATE SUN POSITION ---
                s_az = sun_azimuth(obs, now_utc)
                
                # Map Azimuth to Clock:
                # On our clock: 18:00 is 0°, 12:00 is 90°, 06:00 is 180°, 00:00 is 270°
                # Standard Azimuth: N=0, E=90, S=180, W=270
                # To put Noon at Top (90°):
                # In Northern Hemisphere, Noon is South (180°). So 180° Az -> 90° Clock.
                # In Southern Hemisphere, Noon is North (0°). So 0° Az -> 90° Clock.
                if user_lat >= 0:
                    sun_clock_angle = (270 - s_az) % 360
                else:
                    sun_clock_angle = (90 + s_az) % 360
                    
                sun_rad = math.radians(sun_clock_angle)
                
                # --- 3. ACCURATE MOON POSITION & PHASE ---
                m_az = moon_azimuth(obs, now_utc)
                if user_lat >= 0:
                    moon_clock_angle = (270 - m_az) % 360
                else:
                    moon_clock_angle = (90 + m_az) % 360
                moon_rad = math.radians(moon_clock_angle)
                
                m_phase_val = phase(datetime.date.today())

            except Exception as e:
                # Fallback to geometric math if astral calls fail
                sun_rad = math.radians((18 - current_hour) * 15)
                m_phase_val = 0
                moon_hour = (current_hour - (m_phase_val / 29.53) * 24) % 24
                moon_rad = math.radians((18 - moon_hour) * 15)

            # --- 4. DRAW SUN ---
            sx = center_x + orbit_radius * math.cos(sun_rad)
            sy = center_y - orbit_radius * math.sin(sun_rad)
            sun_r = icon_size / 1.6
            self.canvas.create_oval(
                sx - sun_r, sy - sun_r, sx + sun_r, sy + sun_r,
                fill="#FFD700", outline="#FFA500", width=2
            )

            # --- 5. DRAW MOON ---
            mx = center_x + orbit_radius * math.cos(moon_rad)
            my = center_y - orbit_radius * math.sin(moon_rad)
            
            moon_phases = ["🌕", "🌖", "🌗", "🌘", "🌑", "🌒", "🌓", "🌔"]
            # Round UP to the upcoming phase
            phase_idx = math.ceil((m_phase_val / 29.530588) * 8) % 8

            self.canvas.create_text(
                mx, my,
                text=moon_phases[phase_idx],
                font=("Segoe UI Emoji", icon_size),
                fill="#E0E0E0"
            )

         
        # --- DRAW CLOCK HAND ---
        hand_angle = (18 - current_hour) * 15
        hand_rad = math.radians(hand_angle)
       
        hx = center_x + (radius * 0.75) * math.cos(hand_rad)
        hy = center_y - (radius * 0.75) * math.sin(hand_rad)
       
        arrow_len = radius * 0.08
        self.canvas.create_line(
            center_x, center_y, hx, hy,
            fill="#FF9F1C", width=max(2, int(radius/25)),
            arrow=tk.LAST, arrowshape=(arrow_len, arrow_len, arrow_len/3)
        )


if __name__ == "__main__":
    root = tk.Tk()
    app = ClockWidget(root)
    root.mainloop()